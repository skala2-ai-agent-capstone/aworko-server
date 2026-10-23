package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record RubricSummary(
        @Schema(example = "RUB-quote") String docId,
        @Schema(example = "v2") String version,
        @Schema(example = "quote") String workType,
        @Schema(example = "견적서 작성") String workTypeLabel,
        @Schema(example = "견적서 루브릭") String title,
        @Schema(description = "현행 | 폐기 | 초안", example = "현행") String status,
        LocalDate effectiveFrom,
        @Schema(description = "평가 항목 수", example = "5") int itemCount) {
}
