package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record RubricDetail(RubricSummary rubric, List<Item> items) {

    /** rubric_items 한 줄. score_1이 없으면 0·2점만 있는 항목 */
    public record Item(
            @Schema(example = "q.1") String itemId,
            int seq,
            @Schema(example = "문제 정의") String item,
            @Schema(description = "평가 질문") String criterion,
            @Schema(description = "2점 기준") String score2,
            @Schema(description = "1점 기준(없을 수 있음)") String score1,
            @Schema(description = "0점 기준") String score0,
            @Schema(description = "미관측 조건") String unobserved,
            @Schema(description = "검토 필요 조건") String needsReview,
            @Schema(description = "근거로 볼 자료") List<String> evidenceSources,
            @Schema(description = "관련 조항") List<String> relatedClauses) {
    }
}
