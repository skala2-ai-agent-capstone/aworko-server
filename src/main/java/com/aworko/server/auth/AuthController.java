package com.aworko.server.auth;

import com.aworko.server.global.common.ApiResponse;
import com.aworko.server.global.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "회원 가입·로그인·내 정보")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "로그인", description = "사번·비밀번호로 Access JWT를 발급한다. 이후 요청에 Authorization: Bearer {access_token}을 붙인다.")
    @SecurityRequirements
    @PostMapping("/auth/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @Operation(summary = "회원 가입", description = "사번·비밀번호·이름과 소속 회사·팀 ID(가입용 목록에서 고른 값, 팀 필수)로 "
            + "팀원 계정을 만들고, 로그인과 같은 응답(Access JWT + 내 정보)을 돌려준다. 팀장 가입과 회사·팀 생성은 하지 않는다"
            + "(role을 보내도 무시). 목록에 없거나 그 회사의 팀이 아니면 400, 이미 있는 사번은 409.")
    @SecurityRequirements
    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LoginResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.success(authService.signup(request));
    }

    @Operation(summary = "가입용 회사·팀 목록", description = "가입 화면에서 고를 회사와 팀. 고른 company_id·team_id를 가입 요청에 넣는다.")
    @SecurityRequirements
    @GetMapping("/auth/companies")
    public ApiResponse<List<CompanyOption>> companies() {
        return ApiResponse.success(authService.companyOptions());
    }

    @Operation(summary = "내 정보")
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.success(authService.me(principal));
    }
}
