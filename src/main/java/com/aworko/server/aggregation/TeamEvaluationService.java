package com.aworko.server.aggregation;

import com.aworko.server.aggregation.dto.TeamDashboard;
import com.aworko.server.aggregation.dto.TeamEvaluationDetail;
import com.aworko.server.aggregation.dto.TeamEvaluationRequest;
import com.aworko.server.aggregation.dto.TeamEvaluationSummary;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.messaging.OutboxPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 팀 평가(팀 코칭 리포트) 요청·조회 (ai-agent-capstone docs/team_evaluation.md 「백엔드가 할 일」).
 * <ol>
 *   <li>담당 팀장만. 기간은 팀 대시보드와 같다(끝 날짜 포함, 최대 93일)</li>
 *   <li>공개 승인: 기간에 확정 평가가 있고 최소 인원을 넘는 업무 유형이 모두 같은 기간으로 집계 승인돼 있어야 true.
 *       하나라도 없으면 false로 보내 MCP가 수치 없는 스냅샷을 고정한다(보류 리포트). 승인 안 된 유형은 응답에 알려 준다</li>
 *   <li>business-mcp /internal/team-evaluations로 요청 행·집계 스냅샷을 만든 뒤 TEAM_EVALUATE를 아웃박스로 보낸다</li>
 *   <li>팀 Agent Worker가 리포트를 MCP에 저장하면 team_evaluations가 완료가 된다. 실패는 DLQ로 받아 기록한다</li>
 * </ol>
 * 관리자에게는 팀 리포트만 준다. 개인 평가 상세 열람 권한은 늘리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(TeamEvaluationProperties.class)
public class TeamEvaluationService {

    static final String WAITING = "대기";
    static final String DONE = "완료";
    static final String FAILED = "실패";
    static final String TIMED_OUT = "TIMED_OUT";
    static final int LIST_LIMIT = 20;

    private final TeamAggregationService aggregationService;
    private final TeamEvaluationGateway gateway;
    private final TeamEvaluationRows rows;
    private final OutboxPublisher outboxPublisher;
    private final TransactionTemplate tx;
    private final TeamEvaluationProperties props;
    private final Clock clock;

    public TeamEvaluationSummary request(AuthPrincipal me, String teamId, TeamEvaluationRequest request) {
        // 팀장 권한·기간 검사는 대시보드와 같다. 승인 여부도 같은 기간의 대시보드 승인 기록으로 본다
        TeamDashboard dashboard = aggregationService.dashboard(me, teamId, request.from(), request.to());
        List<TeamDashboard.WorkTypeSection> releasable = dashboard.workTypes().stream()
                .filter(s -> !s.restricted() && s.confirmedCount() > 0).toList();
        List<String> unapproved = releasable.stream().filter(s -> s.approval() == null)
                .map(TeamDashboard.WorkTypeSection::workType).toList();
        boolean released = !releasable.isEmpty() && unapproved.isEmpty();

        String requestId = UUID.randomUUID().toString();
        try {
            gateway.create(me.companyId(), teamId, dashboard.periodFrom(), dashboard.periodTo().plusDays(1), released,
                    requestId, me.userId());
        } catch (TeamEvaluationGateway.Rejected e) {
            throw translate(e);
        }
        // MCP 요청 행이 생긴 뒤에 보낸다. 여기서 실패하면 대기 행만 남고 staleAfter 뒤 실패(TIMED_OUT)로 보인다
        tx.executeWithoutResult(s -> outboxPublisher.teamEvaluate(requestId, me.companyId()));
        return summary(rows.find(me.companyId(), teamId, requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "팀 평가 요청을 찾을 수 없습니다")))
                .withUnapprovedWorkTypes(unapproved);
    }

    public List<TeamEvaluationSummary> list(AuthPrincipal me, String teamId) {
        aggregationService.requireTeamLeadOf(me, teamId);
        return rows.list(me.companyId(), teamId, LIST_LIMIT).stream().map(this::summary).toList();
    }

    public TeamEvaluationDetail detail(AuthPrincipal me, String teamId, String requestId) {
        aggregationService.requireTeamLeadOf(me, teamId);
        if (!isUuid(requestId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "팀 평가 요청을 찾을 수 없습니다");
        }
        TeamEvaluationRows.Row row = rows.find(me.companyId(), teamId, requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "팀 평가 요청을 찾을 수 없습니다"));
        TeamEvaluationSummary summary = summary(row);
        JsonNode report = DONE.equals(summary.status()) ? rows.report(requestId) : null;
        return new TeamEvaluationDetail(summary, report);
    }

    /** team_evaluation.dlq: Worker가 재시도 한도를 넘겼거나 처리할 수 없는 요청 */
    public void fail(JsonNode message, String reason) {
        String requestId = message == null ? null : message.path("request_id").asText(null);
        if (requestId == null || !isUuid(requestId)) {
            log.warn("Team evaluation DLQ message without a valid request_id dropped");
            return;
        }
        if (!rows.recordFailure(requestId, reason == null || reason.isBlank() ? "재시도 한도 초과" : reason, message)) {
            log.warn("Team evaluation DLQ message for unknown or finished request dropped: {}", requestId);
        }
    }

    TeamEvaluationSummary summary(TeamEvaluationRows.Row row) {
        String status;
        String error = null;
        if (DONE.equals(row.status())) {
            status = DONE;
        } else if (row.failed()) {
            status = FAILED;
            error = row.failure();
        } else if (row.requestedAt().isBefore(OffsetDateTime.now(clock).minus(props.staleAfter()))) {
            status = FAILED;
            error = TIMED_OUT;
        } else {
            status = WAITING;
        }
        return new TeamEvaluationSummary(row.requestId(), status, error, row.periodStart(),
                row.periodEnd().minusDays(1), row.releaseApproved(), row.reportStatus(), row.requestedBy(),
                row.requestedAt(), row.savedAt(), null);
    }

    /** 권한·기간은 위에서 확인했으므로 MCP의 FORBIDDEN은 내부 키 설정 오류다 */
    static BusinessException translate(TeamEvaluationGateway.Rejected e) {
        return switch (e.code()) {
            case "VALIDATION_ERROR" -> new BusinessException(ErrorCode.VALIDATION_ERROR, e.getMessage());
            case "NOT_FOUND" -> new BusinessException(ErrorCode.NOT_FOUND, e.getMessage());
            case "VERSION_CONFLICT" -> new BusinessException(ErrorCode.CONFLICT, e.getMessage());
            case "TEMPORARY_FAILURE" -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, e.getMessage());
            default -> {
                log.error("business-mcp rejected team evaluation request: code={}", e.code());
                yield new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "팀 평가 요청을 만들지 못했습니다");
            }
        };
    }

    static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
