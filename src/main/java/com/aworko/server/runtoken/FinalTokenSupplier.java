package com.aworko.server.runtoken;

import com.aworko.server.evaluation.Evaluation;
import com.aworko.server.evaluation.EvaluationRepository;
import com.aworko.server.evaluation.EvaluationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 최종 평가 Worker에 평가별 실행 토큰을 공급한다 (ai-agent-capstone docs/backend_worker_alignment.md 4절).
 * EVALUATE를 보냈고 처리 중인 평가 건(대기·진행 4단계)마다 business-mcp /internal/runs로 토큰을 받아 토큰 파일에 둔다.
 * 다시 발급하면 업무 스냅샷이 새로 고정되므로 남은 수명이 refreshBefore보다 짧을 때만 발급한다(RunTokenFileRefresher).
 * EVALUATE 발행과는 따로 돈다. Worker가 토큰보다 먼저 메시지를 받으면 TOKEN_NOT_READY로 재시도 큐를 거쳐 다시 받는다.
 * app.final-worker.token-file이 비어 있으면 등록하지 않는다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(FinalTokenProperties.class)
@ConditionalOnExpression("!'${app.final-worker.token-file:}'.isBlank()")
public class FinalTokenSupplier {

    private final EvaluationRepository evaluationRepository;
    private final RunTokenGateway gateway;
    private final RunTokenFileRefresher refresher;

    public FinalTokenSupplier(EvaluationRepository evaluationRepository, RunTokenGateway gateway,
                              FinalTokenProperties props, ObjectMapper objectMapper, Clock clock) {
        this.evaluationRepository = evaluationRepository;
        this.gateway = gateway;
        this.refresher = new RunTokenFileRefresher(props.tokenFile(), props.tokenTtl(), props.refreshBefore(),
                objectMapper, clock, "Final");
    }

    @Scheduled(fixedDelayString = "${app.final-worker.poll-interval}")
    public void run() {
        try {
            refresher.logChanges(refresh());
        } catch (RuntimeException e) {
            // DB가 잠깐 끊겨도 스케줄러는 계속 돈다. 토큰 값은 로그에 남기지 않는다
            log.warn("Final run token refresh failed: {}", e.getClass().getSimpleName());
        }
    }

    public RunTokenFileRefresher.Result refresh() {
        return refresher.refresh(evaluationRepository.findQueuedByStatusIn(EvaluationStatus.IN_PROGRESS)
                .stream().map(Evaluation::getEvaluationId).toList(), gateway::issue);
    }
}
