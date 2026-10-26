package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveArtifactRequest(
        @Schema(description = "산출물 본문(마크다운·텍스트, 최대 10만 자)") @NotBlank @Size(max = 100_000) String content) {
}
