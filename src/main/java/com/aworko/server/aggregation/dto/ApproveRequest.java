package com.aworko.server.aggregation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ApproveRequest(
        @Schema(description = "승인할 업무 유형", example = "quote") @NotBlank String workType,
        @Schema(example = "2026-10-19") @NotNull LocalDate from,
        @Schema(example = "2026-10-25") @NotNull LocalDate to) {
}
