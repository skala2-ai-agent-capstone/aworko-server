package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 업무에 지정되었거나(attached) AI가 참고한(accessed) 사내 자료 */
public record InternalReference(
        @Schema(example = "PROD-DOCAI") String docId,
        @Schema(example = "v3.2") String version,
        @Schema(example = "DocAI 제품 설명서") String title,
        @Schema(description = "attached | accessed", example = "attached") String source) {
}
