package com.aworko.server.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 회사·팀은 이름으로 받는다. 같은 이름이 있으면 그 회사·팀에 들어가고, 없으면 새로 만든다. */
public record SignupRequest(
        @Schema(description = "사번. 로그인 ID", example = "1031") @NotNull @Positive Integer employeeNo,
        @Schema(description = "8~64자", example = "password1") @NotBlank @Size(min = 8, max = 64) String password,
        @Schema(example = "가상 직원 B") @NotBlank @Size(max = 50) String name,
        @Schema(description = "member(팀원, 기본값) | team_lead(팀장, 검토·운영 설정 가능)", example = "member")
        @Pattern(regexp = "member|team_lead", message = "member 또는 team_lead만 고를 수 있습니다") String role,
        @Schema(description = "회사 이름. 없는 이름이면 새 회사를 만든다(대소문자·앞뒤 공백 무시)", example = "LG CNS 영업담당 (가상 테넌트)")
        @NotBlank @Size(max = 100) String companyName,
        @Schema(description = "팀 이름(선택). 그 회사에 없는 이름이면 새 팀을 만든다", example = "영업1팀")
        @Size(max = 100) String teamName) {

    @Override
    public String toString() {
        return "SignupRequest[employeeNo=" + employeeNo + ", password=***, name=" + name + ", role=" + role
                + ", companyName=" + companyName + ", teamName=" + teamName + "]";
    }
}
