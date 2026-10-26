package com.aworko.server.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * team_evaluations(MCP 스키마) 읽기와 team_evaluation_failures(V6) 쓰기.
 * 요청 행·스냅샷·리포트는 business-mcp만 쓴다(만들기는 /internal/team-evaluations, 리포트는 팀 Agent의 save 도구).
 */
@Component
@RequiredArgsConstructor
public class TeamEvaluationRows {

    private static final String COLS = "SELECT t.request_id::text AS request_id, t.period_start, t.period_end, "
            + "t.release_approved, t.status, t.requested_by, t.requested_at, t.saved_at, t.report->>'status' AS report_status, "
            + "EXISTS (SELECT 1 FROM team_evaluation_failures f WHERE f.request_id = t.request_id) AS failed, "
            + "(SELECT f.reason FROM team_evaluation_failures f WHERE f.request_id = t.request_id "
            + " ORDER BY f.failed_at DESC, f.id DESC LIMIT 1) AS failure "
            + "FROM team_evaluations t ";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record Row(String requestId, LocalDate periodStart, LocalDate periodEnd, boolean releaseApproved,
                      String status, String requestedBy, OffsetDateTime requestedAt, OffsetDateTime savedAt,
                      String reportStatus, boolean failed, String failure) {
    }

    public List<Row> list(String companyId, String teamId, int limit) {
        return jdbc.query(COLS + "WHERE t.company_id = ? AND t.team_id = ? ORDER BY t.requested_at DESC LIMIT ?",
                this::row, companyId, teamId, limit);
    }

    public Optional<Row> find(String companyId, String teamId, String requestId) {
        return jdbc.query(COLS + "WHERE t.company_id = ? AND t.team_id = ? AND t.request_id = ?::uuid",
                this::row, companyId, teamId, requestId).stream().findFirst();
    }

    public JsonNode report(String requestId) {
        return jdbc.query("SELECT report::text FROM team_evaluations WHERE request_id = ?::uuid",
                (rs, i) -> json(rs.getString(1)), requestId).stream().findFirst().orElse(null);
    }

    /** 실행 토큰을 줄 요청: 리포트 전, 실패 기록 없음, since 이후 요청. 오래된 것부터 */
    public List<String> pendingRequestIds(OffsetDateTime since) {
        return jdbc.queryForList("SELECT t.request_id::text FROM team_evaluations t WHERE t.status = '대기' "
                + "AND t.requested_at > ? AND NOT EXISTS (SELECT 1 FROM team_evaluation_failures f "
                + "WHERE f.request_id = t.request_id) ORDER BY t.requested_at", String.class, since);
    }

    /** DLQ 실패 기록. 없는 요청이거나 이미 완료면 남기지 않는다 */
    public boolean recordFailure(String requestId, String reason, JsonNode message) {
        return jdbc.update("INSERT INTO team_evaluation_failures (request_id, reason, message) "
                + "SELECT request_id, ?, ?::jsonb FROM team_evaluations WHERE request_id = ?::uuid AND status = '대기'",
                reason, message.toString(), requestId) > 0;
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getString("request_id"), rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class), rs.getBoolean("release_approved"), rs.getString("status"),
                rs.getString("requested_by"), rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("saved_at", OffsetDateTime.class), rs.getString("report_status"), rs.getBoolean("failed"),
                rs.getString("failure"));
    }

    private JsonNode json(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
