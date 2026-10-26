package com.aworko.server.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 개인 평가 리포트의 항목·통계 부분. 평가 초안(evaluation_drafts)과 코칭(evaluation_coaching)을 합친 것이다. */
public record EvaluationReport(
        @Schema(description = "채점한 항목 점수 합", example = "7") int totalScore,
        @Schema(description = "채점한 항목 수 × 2 (미관측·검토 필요는 분모에서 제외)", example = "8") int maxScore,
        @Schema(description = "채점 항목 수", example = "4") int scoredCount,
        @Schema(description = "미관측 항목 수 (0점으로 계산하지 않음)", example = "1") int unobservedCount,
        @Schema(description = "검토 필요 항목 수", example = "0") int needsReviewCount,
        @Schema(description = "평가 가능 비율(%) = 채점 항목 ÷ 전체 항목", example = "80") int observedRate,
        List<Item> items) {

    @Schema(name = "EvaluationReportItem")
    public record Item(
            @Schema(example = "quote.3") String itemId,
            @Schema(example = "결과 검증") String name,
            @Schema(description = "채점 | 미관측 | 검토 필요") String status,
            @Schema(description = "0·1·2. 미관측·검토 필요면 null", nullable = true) Integer score,
            @Schema(example = "2점") String scoreLabel,
            @Schema(description = "판단 이유") String reason,
            @Schema(description = "근거 ID (대화·산출물 구간·문서 조항)") List<String> evidenceIds,
            List<Coaching> coaching) {
    }

    public record Coaching(
            @Schema(description = "강점 | 보완점 | 기록 안내") String kind,
            String text,
            @Schema(description = "개선 대상 산출물 구간", nullable = true) String targetSpanId) {
    }

    public static EvaluationReport empty() {
        return new EvaluationReport(0, 0, 0, 0, 0, 0, List.of());
    }
}
