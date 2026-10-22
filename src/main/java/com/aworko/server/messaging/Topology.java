package com.aworko.server.messaging;

/**
 * RabbitMQ 이름. Agent 팀과 공유하는 계약이다 (docs/realtime-feedback-flow.md, 구현 계획 RabbitMQ 절).
 * 바꾸면 Agent Worker도 같이 바꿔야 한다.
 */
public final class Topology {

    private Topology() {
    }

    // 최종 평가: EVALUATE·RESUME
    public static final String EVALUATION_EXCHANGE = "evaluation.exchange";
    public static final String EVALUATION_REQUESTS = "evaluation.requests";
    public static final String EVALUATION_RETRY = "evaluation.retry";
    public static final String EVALUATION_DLQ = "evaluation.dlq";
    public static final String RK_EVALUATE = "evaluate";
    public static final String RK_RESUME = "resume";
    public static final String RK_EVALUATION_RETRY = "evaluation.retry";
    public static final String RK_EVALUATION_DLQ = "evaluation.dlq";

    // 실시간 피드백: LIVE_EVALUATE
    public static final String LIVE_EXCHANGE = "live_evaluation.exchange";
    public static final String LIVE_REQUESTS = "live_evaluation.requests";
    public static final String LIVE_RETRY = "live_evaluation.retry";
    public static final String LIVE_DLQ = "live_evaluation.dlq";
    public static final String RK_LIVE_EVALUATE = "live_evaluate";
    public static final String RK_LIVE_RETRY = "live_evaluation.retry";
    public static final String RK_LIVE_DLQ = "live_evaluation.dlq";
}
