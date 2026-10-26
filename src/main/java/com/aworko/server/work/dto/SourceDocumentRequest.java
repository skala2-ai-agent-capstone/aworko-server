package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 고객 자료(텍스트). PDF·DOCX 등은 프론트에서 텍스트로 바꿔 보내야 한다(서버에 문서 변환기가 없다). */
public record SourceDocumentRequest(
        @Schema(description = "원본 파일 이름. 확장자를 뺀 이름이 doc_id가 된다", example = "RFP_원문_v2.txt")
        @NotBlank @Size(max = 200) String fileName,
        @Schema(description = "본문 텍스트(최대 20만 자)") @NotBlank @Size(max = 200_000) String content,
        @Schema(description = "rfp | rfp_correction | meeting_note | call_note. 비우면 RFP 분석은 rfp, 나머지는 meeting_note",
                example = "rfp") String docType,
        @Schema(description = "등급. 비우면 대외비", example = "대외비") @Size(max = 20) String grade) {
}
