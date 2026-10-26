package com.aworko.server.messaging;

/**
 * RabbitMQ 이름. Agent 팀과 공유하는 계약이다 (docs/realtime-feedback-flow.md, 구현 계획 RabbitMQ 절).
 * 바꾸면 Agent Worker도 같이 바꿔야 한다.
 */
public final class Topology {

    private Topology() {
    }

    // 최종 평가: EVALUATE. 완료(FINAL_EVALUATION_SAVED)는 Agent가 results 큐로 알린다(백엔드 소비).
    // 예전 RESUME(본인 확인·이의·검토 결정)은 큐로 보내지 않고 백엔드 트랜잭션에서 처리한다.
    public static final String EVALUATION_EXCHANGE = "evaluation.exchange";
    public static final String EVALUATION_REQUESTS = "evaluation.requests";
    public static final String EVALUATION_RETRY = "evaluation.retry";
    public static final String EVALUATION_DLQ = "evaluation.dlq";
    public static final String EVALUATION_RESULTS = "evaluation.results";
    public static final String RK_EVALUATE = "evaluate";
    public static final String RK_EVALUATION_RETRY = "evaluation.retry";
    public static final String RK_EVALUATION_DLQ = "evaluation.dlq";
    public static final String RK_EVALUATION_RESULTS = "evaluation.results";

    // 실시간 피드백: LIVE_EVALUATE
    public static final String LIVE_EXCHANGE = "live_evaluation.exchange";
    public static final String LIVE_REQUESTS = "live_evaluation.requests";
    public static final String LIVE_RETRY = "live_evaluation.retry";
    public static final String LIVE_DLQ = "live_evaluation.dlq";
    public static final String RK_LIVE_EVALUATE = "live_evaluate";
    public static final String RK_LIVE_RETRY = "live_evaluation.retry";
    public static final String RK_LIVE_DLQ = "live_evaluation.dlq";

    // 평가 기준 초안 생성: RUBRIC_GENERATE. 완료는 Agent가 results 큐로 알린다(백엔드 소비)
    public static final String RUBRIC_EXCHANGE = "rubric_generation.exchange";
    public static final String RUBRIC_REQUESTS = "rubric_generation.requests";
    public static final String RUBRIC_RETRY = "rubric_generation.retry";
    public static final String RUBRIC_DLQ = "rubric_generation.dlq";
    public static final String RUBRIC_RESULTS = "rubric_generation.results";
    public static final String RK_RUBRIC_GENERATE = "rubric_generate";
    public static final String RK_RUBRIC_RETRY = "rubric_generation.retry";
    public static final String RK_RUBRIC_DLQ = "rubric_generation.dlq";
    public static final String RK_RUBRIC_RESULTS = "rubric_generation.results";

    // 팀 평가: TEAM_EVALUATE. 완료는 팀 Agent가 business-mcp save_team_evaluation_report로 team_evaluations에 남기므로
    // 결과 큐가 없다. 실패만 DLQ로 알린다(백엔드 소비)
    public static final String TEAM_EXCHANGE = "team_evaluation.exchange";
    public static final String TEAM_REQUESTS = "team_evaluation.requests";
    public static final String TEAM_RETRY = "team_evaluation.retry";
    public static final String TEAM_DLQ = "team_evaluation.dlq";
    public static final String RK_TEAM_EVALUATE = "team_evaluate";
    public static final String RK_TEAM_RETRY = "team_evaluation.retry";
    public static final String RK_TEAM_DLQ = "team_evaluation.dlq";
}
