package com.aworko.server.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record ResolveReviewRequest(
        @Schema(description = "keep(유지 → 확정) | override(수정 → 재평가) | hold(보류)", example = "keep")
        @NotNull Decision decision,
        @Schema(description = "검토 의견", example = "원문 근거 확인, 기존 점수 유지") @Size(max = 2000) String note,
        @Schema(description = "override일 때 항목별 요청 점수(선택). 예: {\"quote.3\": 2}", nullable = true)
        Map<String, Integer> newScores) {

    public enum Decision {
        keep, override, hold
    }
}
