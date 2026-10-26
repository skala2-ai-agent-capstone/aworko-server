package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** 사내 자료 열람 결과 */
public record ReferenceDocument(
        String docId,
        String version,
        String title,
        String collection,
        String status,
        @Schema(nullable = true) LocalDate effectiveFrom,
        @Schema(description = "본문 텍스트. 본문이 없는 자료는 빈 문자열") String content) {
}
