package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** 사내 자료(제품 설명서·가격표·내규 등). 본문은 내보내지 않는다 */
public record DocumentSummary(
        @Schema(example = "PROD-DOCAI") String docId,
        @Schema(example = "v3.2") String version,
        @Schema(example = "DocAI 제품 설명서") String title,
        @Schema(description = "references | policies. 기밀(색인 금지) 자료는 생략", example = "references") String collection,
        @Schema(example = "사내") String grade,
        @Schema(description = "현행 | 폐기 | 초안", example = "현행") String status,
        LocalDate effectiveFrom,
        @Schema(example = "제품_서비스_제공범위.pdf") String sourceFile,
        @Schema(description = "검색 색인 대상. false = 기밀(정책 검사에만 사용)") boolean indexed) {
}
