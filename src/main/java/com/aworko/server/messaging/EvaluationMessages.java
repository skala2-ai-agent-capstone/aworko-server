package com.aworko.server.messaging;

import java.util.List;

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

    /** 실시간 피드백 요청. 대화 범위(from_seq~to_seq)는 요청 시점에 고정된다. */
    public record LiveEvaluate(String messageId, String type, String liveEvalId, String companyId, String workId,
                               String requestedBy, int fromSeq, int toSeq, String rubricVersion, int attempt) {
    }

    /** 내규 업로드 뒤 평가 기준 초안 생성. 생성 입력 내규는 policy_doc_id·policy_version으로 고정된다. */
    public record RubricGenerate(String messageId, String type, String generationId, String companyId,
                                 String policyDocId, String policyVersion, List<String> workTypes,
                                 String requestedBy, int attempt) {
    }

    /** 팀 평가 실행. 범위(팀·기간·공개 승인)와 집계 스냅샷은 business-mcp가 request_id에 이미 고정했다 */
    public record TeamEvaluate(String messageId, String type, String requestId, String companyId, int attempt) {
    }
}
