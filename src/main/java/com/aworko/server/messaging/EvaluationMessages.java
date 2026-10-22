package com.aworko.server.messaging;

/**
 * 큐 메시지 본문. 식별자·버전만 담는다 — 업무 원문·대화·개인정보는 넣지 않는다 (기획안 14.1).
 * JSON은 snake_case로 직렬화된다 (spring.jackson.property-naming-strategy).
 */
public final class EvaluationMessages {

    private EvaluationMessages() {
    }

    /** 업무 완료 → 평가 시작 */
    public record Evaluate(String messageId, String type, String evaluationId, String companyId, String taskId,
                           String deliverableVersion, String rubricVersion, int attempt) {
    }

    /** 본인 확인·이의·검토 결정 후 멈춘 평가 재개. action: confirm | appeal | review_decision */
    public record Resume(String messageId, String type, String evaluationId, String action, int attempt) {
    }

    /** 실시간 피드백 요청. 대화 범위(from_seq~to_seq)는 요청 시점에 고정된다. */
    public record LiveEvaluate(String messageId, String type, String liveEvalId, String companyId, String workId,
                               String requestedBy, int fromSeq, int toSeq, String rubricVersion, int attempt) {
    }
}
