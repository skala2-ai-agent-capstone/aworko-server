package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LiveEvaluationRequest(
        @Schema(description = "피드백 받을 업무 ID", example = "W-E2E-QT-08") @NotBlank String workId) {
}
