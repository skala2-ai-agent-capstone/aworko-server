package com.aworko.server.evaluation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 평가 검토 화면: 검토 요청 + 이의 내용 + 평가 리포트(항목·근거·코칭) */
public record ReviewDetail(
        String reviewRequestId,
        JsonNode targetItems,
        String reason,
        @Schema(description = "Agent가 남긴 비교 메모(근거 ID 등)") JsonNode reviewMemo,
        @Schema(description = "직원의 이의 제기. 채점 불일치로 생긴 검토면 null", nullable = true) Appeal appeal,
        EvaluationDetail evaluation) {

    public record Appeal(String comment, JsonNode targetItems, JsonNode citedEvidence, String submittedBy,
                         OffsetDateTime submittedAt) {
    }
}
