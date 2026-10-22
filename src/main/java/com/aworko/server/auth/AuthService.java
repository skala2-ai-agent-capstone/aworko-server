package com.aworko.server.auth;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.global.security.JwtProvider;
import com.aworko.server.organization.User;
import com.aworko.server.organization.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final String BAD_CREDENTIALS = "사번 또는 비밀번호가 올바르지 않습니다";

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

    /**
     * 사번 + 비밀번호(평문) 로그인. 없는 사번·틀린 비밀번호는 같은 메시지로 응답해 계정 존재 여부를 드러내지 않는다.
     */
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmployeeNo(request.employeeNo())
                .filter(u -> matches(u.getPassword(), request.password()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS));

        var principal = new AuthPrincipal(user.getUserId(), user.getCompanyId(), user.getTeamId(), user.getRole());
        JwtProvider.IssuedToken token = jwtProvider.issue(principal);
        return LoginResponse.bearer(token.accessToken(), token.expiresInSeconds(), MeResponse.from(user));
    }

    public MeResponse me(AuthPrincipal principal) {
        return userRepository.findByUserIdAndCompanyId(principal.userId(), principal.companyId())
                .map(MeResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    private static boolean matches(String stored, String given) {
        if (stored == null || given == null) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
