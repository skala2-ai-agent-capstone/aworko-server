package com.aworko.server.work.dto;

import com.aworko.server.work.LiveEvaluation;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record LiveEvaluationResponse(
        @Schema(example = "LIVE-3f2a9c1d0b4e") String liveEvalId,
        String workId,
        @Schema(description = "대기 | 생성 중 | 완료 | 실패. 완료·실패가 될 때까지 2~3초 간격으로 다시 조회", example = "대기")
        String status,
        @Schema(description = "피드백 대상 대화 범위(최근 5개, 요청 시점에 고정)", example = "3") int fromSeq,
        @Schema(example = "7") int toSeq,
        @Schema(description = "피드백 결과(Agent가 채움). 구조는 Agent 팀과 합의 중", nullable = true) JsonNode items,
        @Schema(nullable = true) JsonNode coaching,
        @Schema(description = "실패 사유", nullable = true) String error,
        OffsetDateTime requestedAt) {

    public static LiveEvaluationResponse of(LiveEvaluation e) {
        return new LiveEvaluationResponse(e.getLiveEvalId(), e.getWorkId(), e.getStatus().getCode(), e.getFromSeq(),
                e.getToSeq(), e.getItems(), e.getCoaching(), e.getError(), e.getRequestedAt());
    }
}
