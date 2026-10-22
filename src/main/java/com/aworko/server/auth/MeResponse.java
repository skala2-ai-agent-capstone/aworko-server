package com.aworko.server.auth;

import com.aworko.server.organization.Company;
import com.aworko.server.organization.Team;
import com.aworko.server.organization.User;
import io.swagger.v3.oas.annotations.media.Schema;

/** 로그인 사용자 정보. 프론트 세션 공통 영역(이름·아바타·역할·팀·회사)에 쓰인다. */
public record MeResponse(
        @Schema(example = "E-1021") String userId,
        @Schema(description = "사번", example = "1021") Integer employeeNo,
        @Schema(example = "가상 직원 A") String name,
        @Schema(description = "아바타에 표시할 이름 첫 글자", example = "가") String avatarInitial,
        @Schema(description = "member(팀원) | team_lead(팀장, 검토자 겸임)", example = "member") String role,
        @Schema(example = "C-LGCNS-SALES") String companyId,
        @Schema(example = "LG CNS 영업담당") String companyName,
        @Schema(example = "T-SALES-01") String teamId,
        @Schema(example = "영업1팀") String teamName) {

    public static MeResponse of(User user, Company company, Team team) {
        return new MeResponse(user.getUserId(), user.getEmployeeNo(), user.getName(), initialOf(user.getName()),
                user.getRole().getCode(), user.getCompanyId(), company == null ? null : company.getName(),
                user.getTeamId(), team == null ? null : team.getName());
    }

    static String initialOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.strip().substring(0, name.strip().offsetByCodePoints(0, 1));
    }
}
