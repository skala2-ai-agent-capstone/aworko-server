package com.aworko.server.auth;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.global.security.JwtProvider;
import com.aworko.server.organization.Company;
import com.aworko.server.organization.CompanyRepository;
import com.aworko.server.organization.Team;
import com.aworko.server.organization.TeamRepository;
import com.aworko.server.organization.User;
import com.aworko.server.organization.UserRepository;
import com.aworko.server.organization.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final String BAD_CREDENTIALS = "사번 또는 비밀번호가 올바르지 않습니다";
    private static final String DUPLICATE_EMPLOYEE_NO = "이미 가입된 사번입니다";
    private static final String UNKNOWN_TEAM = "소속 회사·팀을 목록에서 다시 골라 주세요";
    /** 시드(organization.json)의 ID 규칙: 팀원 E-사번(팀장 L-사번은 가입으로 만들지 않는다) */
    private static final String MEMBER_ID_PREFIX = "E-";

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final TeamRepository teamRepository;
    private final JwtProvider jwtProvider;
    private final JdbcTemplate jdbc;

    /**
     * 사번 + 비밀번호(평문) 로그인. 없는 사번·틀린 비밀번호는 같은 메시지로 응답해 계정 존재 여부를 드러내지 않는다.
     */
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmployeeNo(request.employeeNo())
                .filter(u -> matches(u.getPassword(), request.password()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS));
        return issue(user);
    }

    /**
     * 계정을 만들고 로그인 응답을 그대로 돌려준다. 기존 회사·팀(가입 화면 목록의 ID)에 팀원으로만 가입한다.
     * 팀장은 가입으로 만들지 않는다: 서버 주소만 알면 누구나 기존 팀의 팀장이 되어 검토 배정·운영 설정을 가져갈 수 있었다.
     * 팀은 필수다: 팀이 없으면 이의 제기의 검토자가 정해지지 않는다. 회사도 새로 만들지 않는다(새 회사는 내규·기준이 없다).
     * user_id는 시드와 같은 규칙(팀원 E-사번)이다. organization 테이블은 MCP 쪽 스키마라 JdbcTemplate으로 넣는다.
     * 비밀번호는 로그인과 같이 평문 저장.
     */
    @Transactional
    public LoginResponse signup(SignupRequest request) {
        if (userRepository.existsByEmployeeNo(request.employeeNo())) {
            throw new BusinessException(ErrorCode.CONFLICT, DUPLICATE_EMPLOYEE_NO);
        }
        String companyId = request.companyId().strip();
        String teamId = request.teamId().strip();
        Integer teams = jdbc.queryForObject("SELECT count(*) FROM teams WHERE team_id = ? AND company_id = ?",
                Integer.class, teamId, companyId);
        if (teams == null || teams == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, UNKNOWN_TEAM);
        }

        String userId = MEMBER_ID_PREFIX + request.employeeNo();
        try {
            jdbc.update("INSERT INTO users (user_id, employee_no, password, company_id, team_id, name, role) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    userId, request.employeeNo(), request.password(), companyId, teamId, request.name().strip(),
                    UserRole.MEMBER.getCode());
        } catch (DuplicateKeyException e) {
            // 동시에 같은 사번으로 가입했거나 같은 user_id가 이미 있는 경우
            throw new BusinessException(ErrorCode.CONFLICT, DUPLICATE_EMPLOYEE_NO);
        }
        return issue(userRepository.findById(userId).orElseThrow());
    }

    /** 가입 화면의 회사·팀 목록 */
    public List<CompanyOption> companyOptions() {
        Map<String, List<CompanyOption.TeamOption>> teams = new LinkedHashMap<>();
        jdbc.query("SELECT team_id, company_id, name FROM teams ORDER BY name, team_id", rs -> {
            teams.computeIfAbsent(rs.getString("company_id"), k -> new ArrayList<>())
                    .add(new CompanyOption.TeamOption(rs.getString("team_id"), rs.getString("name")));
        });
        return jdbc.query("SELECT company_id, name FROM companies ORDER BY name, company_id", (rs, i) -> new CompanyOption(
                rs.getString("company_id"), rs.getString("name"), teams.getOrDefault(rs.getString("company_id"), List.of())));
    }

    public MeResponse me(AuthPrincipal principal) {
        return userRepository.findByUserIdAndCompanyId(principal.userId(), principal.companyId())
                .map(this::toMe)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    private LoginResponse issue(User user) {
        var principal = new AuthPrincipal(user.getUserId(), user.getCompanyId(), user.getTeamId(), user.getRole());
        JwtProvider.IssuedToken token = jwtProvider.issue(principal);
        return LoginResponse.bearer(token.accessToken(), token.expiresInSeconds(), toMe(user));
    }

    private MeResponse toMe(User user) {
        Company company = companyRepository.findById(user.getCompanyId()).orElse(null);
        Team team = user.getTeamId() == null ? null : teamRepository.findById(user.getTeamId()).orElse(null);
        return MeResponse.of(user, company, team);
    }

    private static boolean matches(String stored, String given) {
        if (stored == null || given == null) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
