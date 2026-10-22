package com.aworko.server.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LoginRequest(
        @Schema(description = "사번", example = "1021") @NotNull @Positive Integer employeeNo,
        @Schema(example = "1234") @NotBlank String password) {

    @Override
    public String toString() {
        return "LoginRequest[employeeNo=" + employeeNo + ", password=***]";
    }
}
