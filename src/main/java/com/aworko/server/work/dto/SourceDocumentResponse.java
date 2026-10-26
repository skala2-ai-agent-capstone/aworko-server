package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record SourceDocumentResponse(
        @Schema(example = "ATT-3F2A9C1D0B4E") String attachmentId,
        @Schema(example = "RFP_원문_v2") String docId,
        @Schema(example = "rfp") String docType,
        @Schema(example = "v1") String version,
        @Schema(example = "대외비") String grade,
        @Schema(description = "올린 파일 이름(시드 자료는 생략)", example = "RFP_원문_v2.pdf") String fileName,
        @Schema(example = "application/pdf") String contentType,
        @Schema(description = "원본 파일 크기(바이트)") Long sizeBytes,
        @Schema(description = "꺼낸 텍스트 글자 수") int length,
        @Schema(description = "텍스트를 꺼냈는지. false면 AI·평가가 이 자료 내용을 읽지 못한다(이미지·HWP 등)")
        boolean textExtracted,
        OffsetDateTime uploadedAt) {
}
