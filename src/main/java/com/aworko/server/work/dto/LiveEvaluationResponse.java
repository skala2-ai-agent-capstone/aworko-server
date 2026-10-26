package com.aworko.server.work.dto;

import com.aworko.server.work.LiveEvaluation;
import com.aworko.server.work.LiveEvaluationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record LiveEvaluationResponse(
        @Schema(example = "LIVE-3f2a9c1d0b4e") String liveEvalId,
        String workId,
        @Schema(description = "대기 | 생성 중 | 완료 | 실패. 완료·실패가 될 때까지 2~3초 간격으로 다시 조회", example = "대기")
        String status,
        @Schema(description = "패널 단계 waiting | generating | done | failed (status와 같은 뜻)", example = "waiting") String phase,
        @Schema(description = "피드백 대상 대화 범위: 최근 완료 질문·응답 최대 5쌍의 첫 질문 seq(요청 시점에 고정)",
                example = "3") int fromSeq,
        @Schema(description = "마지막 답변 seq", example = "12") int toSeq,
        @Schema(description = "피드백 결과(Agent가 채움). 구조는 Agent 팀과 합의 중", nullable = true) JsonNode items,
        @Schema(nullable = true) JsonNode coaching,
        @Schema(description = "실패 사유", nullable = true) String error,
        @Schema(description = "완료일 때 패널 표시용 {items:[{label, state, note}], coaching}", nullable = true)
        LiveFeedbackDisplay display,
        OffsetDateTime requestedAt) {

    public static LiveEvaluationResponse of(LiveEvaluation e, LiveFeedbackDisplay display) {
        return new LiveEvaluationResponse(e.getLiveEvalId(), e.getWorkId(), e.getStatus().getCode(), phase(e.getStatus()),
                e.getFromSeq(), e.getToSeq(), e.getItems(), e.getCoaching(), e.getError(), display, e.getRequestedAt());
    }

    static String phase(LiveEvaluationStatus status) {
        return switch (status) {
            case WAITING -> "waiting";
            case GENERATING -> "generating";
            case COMPLETED -> "done";
            case FAILED -> "failed";
        };
    }
}
