package com.aworko.server.global.security;

import com.aworko.server.organization.UserRole;

/**
 * 인증된 사용자. 컨트롤러에서 {@code @AuthenticationPrincipal AuthPrincipal me}로 받는다.
 * 모든 조회는 companyId 범위로 제한해야 한다 (기획안 19장).
 */
public record AuthPrincipal(String userId, String companyId, String teamId, UserRole role) {

    public boolean hasRole(UserRole r) {
        return role == r;
    }
}
