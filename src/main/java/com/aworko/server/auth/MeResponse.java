package com.aworko.server.auth;

import com.aworko.server.organization.User;

public record MeResponse(String userId, Integer employeeNo, String name, String companyId, String teamId,
                         String role) {

    public static MeResponse from(User user) {
        return new MeResponse(user.getUserId(), user.getEmployeeNo(), user.getName(), user.getCompanyId(),
                user.getTeamId(), user.getRole().getCode());
    }
}
