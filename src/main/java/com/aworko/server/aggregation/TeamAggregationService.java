package com.aworko.server.aggregation;

import com.aworko.server.aggregation.dto.ApproveRequest;
import com.aworko.server.aggregation.dto.TeamDashboard;
import com.aworko.server.evaluation.EvaluationStatus;
import com.aworko.server.global.common.CodedEnum;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.organization.Company;
import com.aworko.server.organization.CompanyRepository;
import com.aworko.server.organization.Team;
import com.aworko.server.organization.TeamRepository;
import com.aworko.server.organization.UserRole;
import com.aworko.server.work.WorkType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 팀 대시보드 조회·집계 승인. 담당 팀장만. 계산 규칙은 TeamAggregationCalculator. */
@Service
@RequiredArgsConstructor
public class TeamAggregationService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 기간 내 팀 업무의 평가 건 + 최신 초안·코칭 */
    private static final String ROWS_SQL = """
            SELECT e.evaluation_id, e.work_id, w.employee_id, w.work_type, e.artifact_version, e.status, e.created_at,
                   d.items AS draft_items, c.items AS coaching_items
              FROM evaluations e
              JOIN work_cases w ON w.work_id = e.work_id
              LEFT JOIN LATERAL (SELECT items FROM evaluation_drafts
                                  WHERE evaluation_id = e.evaluation_id ORDER BY revision DESC LIMIT 1) d ON true
              LEFT JOIN LATERAL (SELECT items FROM evaluation_coaching
                                  WHERE evaluation_id = e.evaluation_id ORDER BY revision DESC LIMIT 1) c ON true
             WHERE e.company_id = ? AND w.team_id = ? AND w.work_type IS NOT NULL
               AND e.created_at >= ? AND e.created_at < ?
            """;

    private final TeamRepository teamRepository;
    private final CompanyRepository companyRepository;
    private final AggregationApprovalRepository approvalRepository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public TeamDashboard dashboard(AuthPrincipal me, String teamId, LocalDate from, LocalDate to) {
        Team team = requireTeamLeadOf(me, teamId);
        Period p = period(from, to);
        int minSize = companyRepository.findById(me.companyId()).map(Company::getMinAggregationSize).orElse(5);
        List<TeamAggregationCalculator.Row> rows = jdbc.query(ROWS_SQL, (rs, i) -> row(rs), me.companyId(), teamId,
                p.from().atStartOfDay(ZONE).toOffsetDateTime(), p.to().plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        return TeamAggregationCalculator.aggregate(team.getTeamId(), team.getName(), p.from(), p.to(), minSize, rows,
                approvals(teamId, p));
    }

    /** 업무 유형별 집계 승인. 최소 인원 미달 유형은 승인할 수 없다(409). */
    @Transactional
    public TeamDashboard.Approval approve(AuthPrincipal me, String teamId, ApproveRequest request) {
        WorkType type = parseWorkType(request.workType());
        TeamDashboard dashboard = dashboard(me, teamId, request.from(), request.to());
        TeamDashboard.WorkTypeSection section = dashboard.workTypes().stream()
                .filter(s -> s.workType().equals(type.getCode())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "해당 기간에 집계할 평가가 없습니다"));
        if (section.restricted() || section.confirmedCount() == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "최소 집계 인원 미달 또는 확정 평가가 없어 승인할 수 없습니다");
        }
        AggregationApproval saved = approvalRepository.save(AggregationApproval.of(me.companyId(), teamId, type,
                dashboard.periodFrom(), dashboard.periodTo(), me.userId(), objectMapper.valueToTree(section)));
        return new TeamDashboard.Approval(saved.getId(), saved.getApprovedBy(), saved.getApprovedAt());
    }

    private Team requireTeamLeadOf(AuthPrincipal me, String teamId) {
        Team team = teamRepository.findByTeamIdAndCompanyId(teamId, me.companyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "팀을 찾을 수 없습니다"));
        if (!me.hasRole(UserRole.TEAM_LEAD) || !teamId.equals(me.teamId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 팀장만 팀 집계를 볼 수 있습니다");
        }
        return team;
    }

    /** 기간을 안 주면 이번 주(월~일) */
    Period period(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        LocalDate f = from != null ? from : today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate t = to != null ? to : f.plusDays(6);
        if (t.isBefore(f) || t.isAfter(f.plusDays(92))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "기간은 시작일 이후 최대 93일까지입니다");
        }
        return new Period(f, t);
    }

    record Period(LocalDate from, LocalDate to) {
    }

    private Map<WorkType, TeamDashboard.Approval> approvals(String teamId, Period p) {
        Map<WorkType, TeamDashboard.Approval> map = new EnumMap<>(WorkType.class);
        for (WorkType type : WorkType.values()) {
            approvalRepository.findByTeamIdAndWorkTypeOrderByApprovedAtDesc(teamId, type).stream()
                    .filter(a -> a.getPeriodFrom().equals(p.from()) && a.getPeriodTo().equals(p.to()))
                    .findFirst()
                    .ifPresent(a -> map.put(type, new TeamDashboard.Approval(a.getId(), a.getApprovedBy(),
                            a.getApprovedAt())));
        }
        return map;
    }

    private TeamAggregationCalculator.Row row(ResultSet rs) throws SQLException {
        return new TeamAggregationCalculator.Row(rs.getString("evaluation_id"), rs.getString("work_id"),
                rs.getString("employee_id"), CodedEnum.fromCode(WorkType.class, rs.getString("work_type")),
                rs.getString("artifact_version"), CodedEnum.fromCode(EvaluationStatus.class, rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class), json(rs.getString("draft_items")),
                json(rs.getString("coaching_items")));
    }

    private JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid jsonb in evaluation items", e);
        }
    }

    private static WorkType parseWorkType(String code) {
        try {
            return CodedEnum.fromCode(WorkType.class, code);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "work_type은 rfp_analysis, proposal, quote, inquiry_reply 중 하나입니다");
        }
    }
}
