package com.aworko.server.runtoken;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 처리할 건(평가 건·팀 평가 요청)마다 실행 토큰을 받아 Worker --token-file에 둔다. 최종 평가·팀 평가 공급기가 같이 쓴다.
 * <ul>
 *   <li>남은 수명이 refreshBefore보다 길면 그대로 둔다(필요할 때만 발급)</li>
 *   <li>목록에서 빠진 건(처리 끝)의 토큰은 파일에서 뺀다</li>
 *   <li>MCP에 닿지 않으면 이번 주기의 발급을 멈추고, 아직 유효한 토큰은 남긴다. 거절된 건만 건너뛰고
 *       1분(REJECT_COOLDOWN) 동안 다시 묻지 않는다(주기가 3초라 로그·MCP 호출이 쌓이지 않게)</li>
 *   <li>Worker 제한(1000개, 1MB, ID 형식)을 넘는 항목은 넣지 않는다. 내용이 같으면 다시 쓰지 않는다</li>
 * </ul>
 */
@Slf4j
public class RunTokenFileRefresher {

    /** {"tokens":{}} 와 여유 */
    private static final int BASE_BYTES = 32;
    static final Duration REJECT_COOLDOWN = Duration.ofMinutes(1);

    /** 거절된 건 → 다시 물어볼 시각. 스케줄러 스레드 하나만 쓴다 */
    private final Map<String, OffsetDateTime> retryAfter = new HashMap<>();
    private final FinalTokenFile file;
    private final Duration tokenTtl;
    private final Duration refreshBefore;
    private final Clock clock;
    private final String label;

    RunTokenFileRefresher(String tokenFile, Duration tokenTtl, Duration refreshBefore, ObjectMapper objectMapper,
                          Clock clock, String label) {
        this.file = new FinalTokenFile(Path.of(tokenFile), objectMapper);
        this.tokenTtl = tokenTtl;
        this.refreshBefore = refreshBefore;
        this.clock = clock;
        this.label = label;
    }

    @FunctionalInterface
    interface Issuer {
        RunTokenGateway.IssuedToken issue(String id, Duration ttl);
    }

    public record Result(int tokens, int issued, int removed, boolean written) {
    }

    /** @param ids 토큰이 필요한 건, 먼저 처리될 것부터(파일이 가득 차면 뒤의 건이 기다린다) */
    Result refresh(List<String> ids, Issuer issuer) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<String, FinalTokenFile.Entry> current = file.read();
        Map<String, FinalTokenFile.Entry> next = new LinkedHashMap<>();
        int issued = 0;
        int bytes = BASE_BYTES;
        boolean mcpAvailable = true;
        retryAfter.keySet().retainAll(ids);
        for (String id : ids) {
            if (next.size() >= FinalTokenFile.MAX_TOKENS) {
                log.warn("{} run token file is full ({} tokens); newer ones wait", label, FinalTokenFile.MAX_TOKENS);
                break;
            }
            FinalTokenFile.Entry entry = current.get(id);
            boolean fresh = entry != null && entry.expiresAt().minus(refreshBefore).isAfter(now);
            boolean cooling = retryAfter.containsKey(id) && now.isBefore(retryAfter.get(id));
            if (!fresh && mcpAvailable && !cooling) {
                try {
                    RunTokenGateway.IssuedToken token = issuer.issue(id, tokenTtl);
                    entry = new FinalTokenFile.Entry(token.token(), token.expiresAt());
                    retryAfter.remove(id);
                    issued++;
                } catch (RunTokenGateway.Rejected e) {
                    log.warn("{} run token rejected: id={}, code={}", label, id, e.code());
                    retryAfter.put(id, now.plus(REJECT_COOLDOWN));
                } catch (RunTokenGateway.Unavailable e) {
                    log.warn("business-mcp unavailable for {} run tokens: {}", label, e.getMessage());
                    mcpAvailable = false;
                }
            }
            if (entry == null || !entry.expiresAt().isAfter(now)) {
                continue;
            }
            if (!FinalTokenFile.acceptable(id, entry)) {
                log.warn("{} run token skipped: id or token is outside the worker file format", label);
                continue;
            }
            int size = FinalTokenFile.entryBytes(id, entry);
            if (bytes + size > FinalTokenFile.MAX_BYTES) {
                log.warn("{} run token file reached {} bytes; newer ones wait", label, FinalTokenFile.MAX_BYTES);
                break;
            }
            bytes += size;
            next.put(id, entry);
        }

        boolean write = !next.equals(current) || !Files.isRegularFile(file.path());
        if (write) {
            try {
                file.write(next);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        int removed = (int) current.keySet().stream().filter(id -> !next.containsKey(id)).count();
        return new Result(next.size(), issued, removed, write);
    }

    void logChanges(Result result) {
        if (result.issued() > 0 || result.removed() > 0) {
            log.info("{} run tokens: tokens={}, issued={}, removed={}", label, result.tokens(), result.issued(),
                    result.removed());
        }
    }
}
