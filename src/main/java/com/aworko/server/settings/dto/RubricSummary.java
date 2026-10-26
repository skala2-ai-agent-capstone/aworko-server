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
        @Schema(description = "평가 항목 수", example = "5") int itemCount,
        @Schema(description = "만든 곳·승인 기록. 초안이면 '평가 기준 생성 Agent' 또는 '관리자 수정 초안(이름)'",
                example = "관리자 수정 초안(권하림) · 승인 권하림") String note) {
}
