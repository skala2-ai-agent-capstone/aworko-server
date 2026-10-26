package com.aworko.server.settings;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.messaging.OutboxPublisher;
import com.aworko.server.settings.dto.PolicyDocumentSaved;
import com.aworko.server.settings.dto.PolicyUploadResult;
import com.aworko.server.settings.dto.RubricGeneration;
import com.aworko.server.work.WorkType;
import com.aworko.server.work.dto.FileUpload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 내규 업로드 → 평가 기준 초안 자동 생성(운영 설정 화면의 '평가 기준 설계 에이전트').
 * 1) business-mcp에 내규를 적재하고(현행 전환·검색 색인) 2) rubric_generations 행과 RUBRIC_GENERATE 아웃박스를
 * 한 트랜잭션에 쓴다. Agent가 save_rubric으로 초안을 저장한 뒤 results 큐로 알리면 완료, DLQ로 가면 실패가 된다.
 * 초안은 관리자가 승인해야 현행이 된다(SettingsService.approve). MCP를 부르는 동안 DB 트랜잭션을 잡지 않는다.
 */
@Slf4j
@Service
public class RubricGenerationService {

    static final String PENDING = "대기";
    static final String DONE = "완료";
    static final String FAILED = "실패";
    static final String TIMED_OUT = "TIMED_OUT";
    static final List<String> WORK_TYPES = Arrays.stream(WorkType.values()).map(WorkType::getCode).toList();

    /** Worker 오류 코드 → 화면 설명. 모르는 코드는 기본 설명 */
    private static final Map<String, String> ERROR_MESSAGES = Map.of(
            TIMED_OUT, "기준 생성 Worker가 응답하지 않았습니다. 다시 생성해 주세요",
            "POLICY_INCOMPLETE", "내규에서 평가 기준에 필요한 조항을 찾지 못했습니다",
            "VERSION_CONFLICT", "검색 색인이 아직 새 내규로 바뀌지 않았습니다. 잠시 뒤 다시 생성해 주세요",
            "RUBRIC_INVALID", "생성한 기준이 형식·근거 검사를 통과하지 못했습니다. 다시 생성해 주세요",
            "TOKEN_SCOPE_MISMATCH", "기준 생성 Worker 토큰의 회사가 다릅니다(운영 설정 확인 필요)",
            "TOKEN_REJECTED", "기준 생성 Worker 토큰이 거부되었습니다(만료·종류 확인 필요)",
            "LLM_UNAVAILABLE", "기준 생성 모델에 연결하지 못했습니다. 잠시 뒤 다시 생성해 주세요",
            "MCP_UNAVAILABLE", "내규 검색·기준 저장 서버에 연결하지 못했습니다. 잠시 뒤 다시 생성해 주세요",
            "INVALID_EVENT", "생성 요청 형식이 올바르지 않습니다");
    private static final String DEFAULT_ERROR_MESSAGE = "평가 기준 초안을 만들지 못했습니다. 다시 생성해 주세요";
    private static final String SELECT = "SELECT generation_id, status, policy_doc_id, policy_version, source_file, work_types, "
            + "drafts, error, requested_by, created_at, finished_at FROM rubric_generations ";

    private final JdbcTemplate jdbc;
    private final PolicyDocumentGateway documents;
    private final OutboxPublisher outboxPublisher;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Duration staleAfter;

    public RubricGenerationService(JdbcTemplate jdbc, PolicyDocumentGateway documents, OutboxPublisher outboxPublisher,
                                   TransactionTemplate tx, ObjectMapper objectMapper,
                                   @Value("${app.rubric-generation.stale-after:30m}") Duration staleAfter) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.outboxPublisher = outboxPublisher;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.staleAfter = staleAfter;
    }

    /** 내규를 새 버전으로 적재하고 평가 기준 초안 생성을 시작한다. 같은 내용을 다시 올리면 생성하지 않는다 */
    public PolicyUploadResult upload(AuthPrincipal me, FileUpload file, String version, String title,
                                     LocalDate effectiveFrom) {
        SettingsService.requireTeamLead(me);
        String fileName = file.fileName() == null ? "" : file.fileName().strip();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".pdf") && !lower.endsWith(".txt")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "내규는 .pdf 또는 .txt 파일만 올릴 수 있습니다");
        }
        if (file.data() == null || file.data().length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "빈 파일입니다");
        }
        if (version == null || version.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "버전(예 v1.2)을 입력하세요");
        }
        // 생성 중에 내규가 바뀌면 그 생성은 이전 버전으로 초안을 만든다. 끝난 뒤 올리게 한다
        requireNoPending(me.companyId());
        PolicyDocumentSaved saved;
        try {
            saved = documents.upload(new PolicyDocumentGateway.PolicyUpload(me.companyId(), version.strip(), fileName,
                    file.data(), blankToNull(title), effectiveFrom, nameOf(me)));
        } catch (RubricGateway.Rejected e) {
            throw SettingsService.translate(e);
        }
        if (saved.duplicate()) {
            return new PolicyUploadResult(saved, null);
        }
        RubricGeneration generation = tx.execute(s -> request(me, saved.docId(), saved.version(), fileName));
        return new PolicyUploadResult(saved, generation);
    }

    /** 현행 내규로 초안을 다시 만든다(실패·시간 초과 뒤, 또는 같은 내용을 다시 올렸을 때) */
    public RubricGeneration regenerate(AuthPrincipal me) {
        SettingsService.requireTeamLead(me);
        requireNoPending(me.companyId());
        Map<String, Object> policy = jdbc.queryForList("SELECT doc_id, version, source_file FROM documents "
                        + "WHERE company_id = ? AND collection = 'policies' AND status = '현행' "
                        + "ORDER BY effective_from DESC NULLS LAST, version DESC LIMIT 1", me.companyId()).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "현행 내규가 없습니다. 내규를 먼저 올리세요"));
        return tx.execute(s -> request(me, (String) policy.get("doc_id"), (String) policy.get("version"),
                (String) policy.get("source_file")));
    }

    /** 회사의 가장 최근 생성. 없으면 null. 오래 대기 중인 건은 시간 초과 실패로 보여 준다(다음 요청 때 기록) */
    @Transactional(readOnly = true)
    public RubricGeneration latest(AuthPrincipal me) {
        SettingsService.requireTeamLead(me);
        return jdbc.query(SELECT + "WHERE company_id = ? ORDER BY created_at DESC LIMIT 1", this::row, me.companyId())
                .stream().findFirst().orElse(null);
    }

    /** results 큐: Agent가 초안을 모두 저장했다. 이미 끝난 건(재전달)은 무시한다. 시간 초과로 실패 처리한 건은 완료로 바로잡는다 */
    @Transactional
    public void complete(JsonNode message) {
        String generationId = text(message, "generation_id");
        String companyId = text(message, "company_id");
        if (generationId == null || companyId == null) {
            log.warn("Rubric generation result without generation_id/company_id dropped: message_id={}",
                    text(message, "message_id"));
            return;
        }
        List<RubricGeneration.Draft> drafts = new ArrayList<>();
        message.path("drafts").forEach(d -> drafts.add(new RubricGeneration.Draft(text(d, "work_type"),
                text(d, "doc_id"), text(d, "version"))));
        int updated = jdbc.update("UPDATE rubric_generations SET status = ?, drafts = ?::jsonb, error = NULL, "
                        + "finished_at = now() WHERE generation_id = ? AND company_id = ? "
                        + "AND (status = ? OR (status = ? AND error = ?))",
                DONE, json(drafts), generationId, companyId, PENDING, FAILED, TIMED_OUT);
        if (updated == 0) {
            log.info("Rubric generation result ignored (unknown or already finished): {}", generationId);
        }
    }

    /** DLQ: Agent가 재시도 한도를 넘겼거나 처리할 수 없는 요청이다 */
    @Transactional
    public void fail(JsonNode message, String reason) {
        String generationId = text(message, "generation_id");
        if (generationId == null) {
            log.warn("Rubric generation DLQ message without generation_id dropped: message_id={}",
                    text(message, "message_id"));
            return;
        }
        int updated = jdbc.update("UPDATE rubric_generations SET status = ?, error = ?, finished_at = now() "
                        + "WHERE generation_id = ? AND status = ?",
                FAILED, reason == null || reason.isBlank() ? "RETRY_EXHAUSTED" : reason, generationId, PENDING);
        if (updated == 0) {
            log.info("Rubric generation DLQ message ignored (unknown or already finished): {}", generationId);
        }
    }

    private RubricGeneration request(AuthPrincipal me, String docId, String version, String sourceFile) {
        String generationId = "RG-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            jdbc.update("INSERT INTO rubric_generations (generation_id, company_id, policy_doc_id, policy_version, "
                            + "source_file, work_types, requested_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    generationId, me.companyId(), docId, version, sourceFile, WORK_TYPES.toArray(String[]::new),
                    me.userId());
        } catch (DuplicateKeyException e) {
            // 동시에 두 번 눌렀을 때. 회사마다 대기 중인 생성은 하나(ux_rubric_generations_pending)
            throw pendingConflict();
        }
        outboxPublisher.rubricGenerate(generationId, me.companyId(), docId, version, WORK_TYPES, me.userId());
        return jdbc.queryForObject(SELECT + "WHERE generation_id = ?", this::row, generationId);
    }

    /** 오래 대기 중인 건은 Worker가 없거나 멈춘 것으로 보고 실패로 기록한 뒤, 아직 대기 중인 건이 있으면 409 */
    private void requireNoPending(String companyId) {
        jdbc.update("UPDATE rubric_generations SET status = ?, error = ?, finished_at = now() "
                        + "WHERE company_id = ? AND status = ? AND created_at < ?",
                FAILED, TIMED_OUT, companyId, PENDING, OffsetDateTime.now().minus(staleAfter));
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM rubric_generations WHERE company_id = ? AND status = ?",
                Integer.class, companyId, PENDING);
        if (pending != null && pending > 0) {
            throw pendingConflict();
        }
    }

    private static BusinessException pendingConflict() {
        return new BusinessException(ErrorCode.CONFLICT, "평가 기준 초안을 만드는 중입니다. 끝난 뒤 다시 시도하세요");
    }

    private RubricGeneration row(ResultSet rs, int i) throws SQLException {
        String status = rs.getString("status");
        String error = rs.getString("error");
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        if (PENDING.equals(status) && createdAt.isBefore(OffsetDateTime.now().minus(staleAfter))) {
            status = FAILED;
            error = TIMED_OUT;
        }
        return new RubricGeneration(rs.getString("generation_id"), status, rs.getString("policy_doc_id"),
                rs.getString("policy_version"), rs.getString("source_file"), texts(rs.getArray("work_types")),
                drafts(rs.getString("drafts")), error,
                error == null ? null : ERROR_MESSAGES.getOrDefault(error, DEFAULT_ERROR_MESSAGE),
                rs.getString("requested_by"), createdAt, rs.getObject("finished_at", OffsetDateTime.class));
    }

    private List<RubricGeneration.Draft> drafts(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Arrays.asList(objectMapper.readValue(raw, RubricGeneration.Draft[].class));
        } catch (JsonProcessingException e) {
            log.warn("Unreadable rubric_generations.drafts ignored");
            return null;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 내규 문서 note(관리자 업로드(이름))에 남길 이름 */
    private String nameOf(AuthPrincipal me) {
        return jdbc.queryForList("SELECT name FROM users WHERE user_id = ?", String.class, me.userId()).stream()
                .findFirst().orElse(me.userId());
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
