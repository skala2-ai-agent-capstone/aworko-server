package com.aworko.server.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 가입은 기존 회사·팀(GET /api/auth/companies 목록의 ID)에 팀원으로만 한다.
 * 회사·팀을 새로 만들거나 팀장으로 가입하지 않는다(role을 보내도 무시한다).
 */
public record SignupRequest(
        @Schema(description = "사번. 로그인 ID", example = "1031") @NotNull @Positive Integer employeeNo,
        @Schema(description = "8~64자", example = "password1") @NotBlank @Size(min = 8, max = 64) String password,
        @Schema(example = "가상 직원 B") @NotBlank @Size(max = 50) String name,
        @Schema(description = "소속 회사 ID. GET /api/auth/companies 목록에서 고른다", example = "C-LGCNS-SALES")
        @NotBlank @Size(max = 64) String companyId,
        @Schema(description = "소속 팀 ID(필수). 그 회사의 팀이어야 한다", example = "T-SALES-01")
        @NotBlank @Size(max = 64) String teamId) {

    @Override
    public String toString() {
        return "SignupRequest[employeeNo=" + employeeNo + ", password=***, name=" + name + ", companyId=" + companyId
                + ", teamId=" + teamId + "]";
    }
}
