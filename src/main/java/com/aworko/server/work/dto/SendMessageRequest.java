package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @Schema(description = "보낼 메시지", example = "이 RFP에서 보안 요구사항만 뽑아서 정리해줘") @NotBlank @Size(max = 8000)
        String content) {
}
