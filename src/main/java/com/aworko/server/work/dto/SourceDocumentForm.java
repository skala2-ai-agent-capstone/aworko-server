package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.multipart.MultipartFile;

/** 고객 자료 올리기 multipart 폼 칸(Swagger 문서용) */
public record SourceDocumentForm(
        @Schema(description = "고객 자료 파일(20MB까지)", requiredMode = Schema.RequiredMode.REQUIRED) MultipartFile file,
        @Schema(description = "rfp | rfp_correction | meeting_note | call_note. 비우면 RFP 분석은 rfp, 나머지는 meeting_note")
        String docType) {
}
