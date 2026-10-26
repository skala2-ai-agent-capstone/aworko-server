package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record AttachReferenceRequest(
        @Schema(description = "GET /api/references의 doc_id", example = "PROD-DOCAI") @NotBlank String docId,
        @Schema(description = "GET /api/references의 version", example = "v3.2") @NotBlank String version) {
}
