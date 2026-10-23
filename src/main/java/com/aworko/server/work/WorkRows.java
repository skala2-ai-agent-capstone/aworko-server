package com.aworko.server.work;

import com.aworko.server.evaluation.EvaluationStatus;
import com.aworko.server.global.common.CodedEnum;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.organization.UserRole;
import com.aworko.server.work.dto.WorkSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 업무 목록 한 줄(업무 + 최근 산출물·평가·대화 시각)과 권한 확인.
 * 담당자 본인은 원문까지, 같은 팀 팀장은 마스킹본만 읽는다. 쓰기(대화·자료·산출물)는 본인만.
 */
@Component
@RequiredArgsConstructor
public class WorkRows {

    static final String STATUS_IN_PROGRESS = "in_progress";
    static final String STATUS_COMPLETED = "completed";
    static final List<String> STAGES = List.of("작성 중", "평가 대기", "평가 중", "확인 대기", "검토 중", "확정", "자료 보류", "평가 실패");

    private static final String SQL = """
            SELECT w.work_id, w.team_id, w.employee_id, u.name AS employee_name, w.title, w.work_type,
                   w.customer_id, c.name AS customer_name, w.status, w.created_at, w.completed_at,
                   a.version AS artifact_version, e.evaluation_id, e.status AS evaluation_status,
                   greatest(w.created_at, w.completed_at, a.saved_at, e.updated_at, x.last_chat_at) AS updated_at
              FROM work_cases w
              JOIN users u ON u.user_id = w.employee_id
              LEFT JOIN customers c ON c.customer_id = w.customer_id
              LEFT JOIN LATERAL (SELECT version, saved_at FROM artifact_versions
                                  WHERE work_id = w.work_id ORDER BY saved_at DESC, version DESC LIMIT 1) a ON true
              LEFT JOIN LATERAL (SELECT evaluation_id, status, updated_at FROM evaluations
                                  WHERE work_id = w.work_id ORDER BY created_at DESC, evaluation_id DESC LIMIT 1) e ON true
              LEFT JOIN LATERAL (SELECT max(created_at) AS last_chat_at FROM llm_exchanges
                                  WHERE work_id = w.work_id AND stage = 'workspace_chat') x ON true
             WHERE w.company_id = ?
            """;
    private static final String ORDER = " ORDER BY updated_at DESC, w.work_id";

    private final JdbcTemplate jdbc;

    /** 업무 + 목록 표시용 요약 */
    public record Row(WorkSummary summary, String teamId, String customerId, OffsetDateTime createdAt,
                      OffsetDateTime completedAt) {

        public boolean ownedBy(AuthPrincipal me) {
            return summary.employeeId().equals(me.userId());
        }

        public WorkType workType() {
            return summary.workType() == null ? null : CodedEnum.fromCode(WorkType.class, summary.workType());
        }
    }

    public List<Row> byEmployee(AuthPrincipal me) {
        return jdbc.query(SQL + " AND w.employee_id = ?" + ORDER, this::row, me.companyId(), me.userId());
    }

    public List<Row> byTeam(AuthPrincipal me) {
        return jdbc.query(SQL + " AND w.team_id = ?" + ORDER, this::row, me.companyId(), me.teamId());
    }

    /** 담당자 본인 또는 같은 팀 팀장. 다른 회사 업무는 없는 것으로 본다(404) */
    public Row readable(AuthPrincipal me, String workId) {
        Row row = find(me, workId);
        boolean lead = me.hasRole(UserRole.TEAM_LEAD) && row.teamId() != null && row.teamId().equals(me.teamId());
        if (!row.ownedBy(me) && !lead) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 업무 또는 담당 팀 업무만 볼 수 있습니다");
        }
        return row;
    }

    /** 담당자 본인만 */
    public Row owned(AuthPrincipal me, String workId) {
        Row row = find(me, workId);
        if (!row.ownedBy(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 업무만 수정할 수 있습니다");
        }
        return row;
    }

    private Row find(AuthPrincipal me, String workId) {
        return jdbc.query(SQL + " AND w.work_id = ?", this::row, me.companyId(), workId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "업무를 찾을 수 없습니다"));
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        String type = rs.getString("work_type");
        String status = rs.getString("status");
        String evaluationStatus = rs.getString("evaluation_status");
        WorkType workType = type == null ? null : CodedEnum.fromCode(WorkType.class, type);
        WorkSummary summary = new WorkSummary(rs.getString("work_id"), rs.getString("title"), type,
                workType == null ? null : workType.getLabel(), WorkTypeCatalog.typeKey(workType), rs.getString("customer_name"),
                rs.getString("employee_id"), rs.getString("employee_name"), status, stage(status, evaluationStatus),
                rs.getString("evaluation_id"), evaluationStatus, rs.getString("artifact_version"),
                rs.getObject("updated_at", OffsetDateTime.class));
        return new Row(summary, rs.getString("team_id"), rs.getString("customer_id"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class));
    }

    /** 화면 단계: 평가가 있으면 최근 평가 상태, 없으면 업무 상태로 정한다 */
    static String stage(String workStatus, String evaluationStatus) {
        if (evaluationStatus == null) {
            return STATUS_COMPLETED.equals(workStatus) ? "평가 대기" : "작성 중";
        }
        return switch (CodedEnum.fromCode(EvaluationStatus.class, evaluationStatus)) {
            case AWAITING_CONFIRMATION -> "확인 대기";
            case AWAITING_REVIEW -> "검토 중";
            case CONFIRMED -> "확정";
            case ON_HOLD -> "자료 보류";
            case FAILED -> "평가 실패";
            default -> "평가 중";
        };
    }
}
