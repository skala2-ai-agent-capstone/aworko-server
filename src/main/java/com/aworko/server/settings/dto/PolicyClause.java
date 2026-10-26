package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 현행 내규 조항 하나. 평가 기준의 related_clauses로 고를 수 있는 값이다. text는 조항 첫 단락 */
public record PolicyClause(
        @Schema(example = "SALES-AI-GL-001") String docId,
        @Schema(example = "v1.0") String version,
        @Schema(example = "ART-11") String clauseId,
        @Schema(example = "제11조 (업무 등록)") String label,
        String text) {
}
