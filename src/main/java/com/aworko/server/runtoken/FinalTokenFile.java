package com.aworko.server.runtoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Worker --token-file 형식: {"tokens": {"EVAL-…": {"token": "…", "expires_at": "2026-10-23T17:00:00+09:00"}}}.
 * Worker가 매번 새로 읽으므로 같은 디렉터리의 임시 파일에 쓴 뒤 이름을 바꿔(원자적 교체) 반쯤 쓴 파일을 읽지 않게 한다.
 * 권한은 소유자만 읽기·쓰기(600). 백엔드와 Worker 이미지는 같은 uid(10001)로 돈다.
 */
public class FinalTokenFile {

    /** Worker 제한(final_tokens.py): 파일 1MB, 토큰 1000개, 토큰 16384자, evaluation_id 형식 */
    static final int MAX_BYTES = 1024 * 1024;
    static final int MAX_TOKENS = 1000;
    static final int MAX_TOKEN_LENGTH = 16384;
    static final String ID_PATTERN = "^[A-Za-z0-9_.:-]{1,160}$";

    private final Path path;
    private final ObjectMapper objectMapper;

    public FinalTokenFile(Path path, ObjectMapper objectMapper) {
        this.path = path;
        this.objectMapper = objectMapper;
    }

    public record Entry(String token, OffsetDateTime expiresAt) {
    }

    public Path path() {
        return path;
    }

    /** 지금 파일의 토큰. 없거나 깨졌으면 빈 목록(다음 쓰기에서 새로 만든다) */
    public Map<String, Entry> read() {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return out;
        }
        try {
            JsonNode tokens = objectMapper.readTree(path.toFile()).path("tokens");
            tokens.fields().forEachRemaining(e -> {
                String token = e.getValue().path("token").asText("");
                String expiresAt = e.getValue().path("expires_at").asText("");
                if (!token.isBlank() && !expiresAt.isBlank()) {
                    out.put(e.getKey(), new Entry(token, OffsetDateTime.parse(expiresAt)));
                }
            });
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** Worker가 받을 수 있는 항목인지(형식이 하나라도 틀리면 Worker는 파일 전체를 거부한다) */
    static boolean acceptable(String evaluationId, Entry entry) {
        return evaluationId.matches(ID_PATTERN) && entry.token().length() <= MAX_TOKEN_LENGTH
                && !entry.token().isBlank();
    }

    public void write(Map<String, Entry> tokens) throws IOException {
        byte[] body = render(tokens);
        if (body.length > MAX_BYTES) {
            throw new IOException("token file exceeds " + MAX_BYTES + " bytes");
        }
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + path.getFileName() + ".", ".tmp");
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            }
            Files.write(tmp, body);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 항목 하나가 파일에서 차지하는 바이트의 상한 추정. ID·토큰은 ASCII(ID_PATTERN, base64url)라 글자 수 = 바이트 수.
     * {"<id>":{"token":"<t>","expires_at":"2026-10-23T17:00:00+09:00"}, 에 여유를 더했다.
     */
    static int entryBytes(String evaluationId, Entry entry) {
        return evaluationId.length() + entry.token().length() + 64;
    }

    private byte[] render(Map<String, Entry> tokens) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode map = root.putObject("tokens");
        tokens.forEach((id, entry) -> map.putObject(id)
                .put("token", entry.token())
                .put("expires_at", entry.expiresAt().truncatedTo(ChronoUnit.SECONDS)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
}
