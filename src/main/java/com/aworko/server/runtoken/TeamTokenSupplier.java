package com.aworko.server.runtoken;

import com.aworko.server.aggregation.TeamEvaluationProperties;
import com.aworko.server.aggregation.TeamEvaluationRows;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 팀 평가 Worker에 요청별 실행 토큰을 공급한다 (ai-agent-capstone docs/team_evaluation.md 「백엔드가 할 일」 3).
 * 리포트 전·실패 기록 없음·staleAfter 안의 팀 평가 요청마다 business-mcp /internal/team-runs로 토큰을 받아 토큰 파일에 둔다.
 * TEAM_EVALUATE 발행과는 따로 돈다. Worker가 토큰보다 먼저 메시지를 받으면 TOKEN_NOT_READY로 재시도 큐를 거친다.
 * app.team-worker.token-file이 비어 있으면 등록하지 않는다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(TeamTokenProperties.class)
@ConditionalOnExpression("!'${app.team-worker.token-file:}'.isBlank()")
public class TeamTokenSupplier {

    private final TeamEvaluationRows rows;
    private final TeamRunTokenGateway gateway;
    private final TeamEvaluationProperties teamProps;
    private final Clock clock;
    private final RunTokenFileRefresher refresher;

    public TeamTokenSupplier(TeamEvaluationRows rows, TeamRunTokenGateway gateway, TeamTokenProperties props,
                             TeamEvaluationProperties teamProps, ObjectMapper objectMapper, Clock clock) {
        this.rows = rows;
        this.gateway = gateway;
        this.teamProps = teamProps;
        this.clock = clock;
        this.refresher = new RunTokenFileRefresher(props.tokenFile(), props.tokenTtl(), props.refreshBefore(),
                objectMapper, clock, "Team");
    }

    @Scheduled(fixedDelayString = "${app.team-worker.poll-interval}")
    public void run() {
        try {
            refresher.logChanges(refresh());
        } catch (RuntimeException e) {
            log.warn("Team run token refresh failed: {}", e.getClass().getSimpleName());
        }
    }

    public RunTokenFileRefresher.Result refresh() {
        OffsetDateTime since = OffsetDateTime.now(clock).minus(teamProps.staleAfter());
        return refresher.refresh(rows.pendingRequestIds(since), gateway::issueTeam);
    }
}
