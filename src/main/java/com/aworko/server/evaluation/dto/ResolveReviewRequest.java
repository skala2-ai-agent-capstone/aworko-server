package com.aworko.server.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record ResolveReviewRequest(
        @Schema(description = "keep(유지 → 확정) | override(팀장 점수 반영 → 확인 대기) | hold(보류)", example = "keep")
        @NotNull Decision decision,
        @Schema(description = "검토 의견. override면 고친 항목의 사유로도 남는다", example = "원문 근거 확인, 기존 점수 유지")
        @Size(max = 2000) String note,
        @Schema(description = "override일 때 필수. 항목별 새 점수(0~2, 보안 준수는 0·2). 예: {\"quote.3\": 2}", nullable = true)
        Map<String, Integer> newScores) {

    public enum Decision {
        keep, override, hold
    }
}
