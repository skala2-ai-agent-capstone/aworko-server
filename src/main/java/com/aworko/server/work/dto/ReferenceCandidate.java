package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** 업무에 지정할 수 있는 사내 자료 한 버전 */
public record ReferenceCandidate(
        @Schema(example = "PROD-DOCAI") String docId,
        @Schema(example = "v3.2") String version,
        @Schema(example = "스마트 문서 AI(Doc AI) 제품 설명서") String title,
        @Schema(description = "references(제품·가격 자료) | policies(내규·정책)", example = "references") String collection,
        @Schema(description = "현행 | 폐기 | 초안. 맞는 버전을 고르는 것도 평가 대상이라 이전 버전도 함께 나온다", example = "현행")
        String status,
        @Schema(nullable = true) LocalDate effectiveFrom) {
}
