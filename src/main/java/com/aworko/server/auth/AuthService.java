package com.aworko.server.auth;

import com.aworko.server.global.common.CodedEnum;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
    /** 시드(organization.json)의 ID 규칙: 팀원 E-사번, 팀장 L-사번 */
    private static final String MEMBER_ID_PREFIX = "E-";
    private static final String LEAD_ID_PREFIX = "L-";

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
     * 계정을 만들고 로그인 응답을 그대로 돌려준다. 역할은 가입자가 고른다(없으면 member).
     * 회사·팀은 이름으로 찾고, 없으면 만든다. user_id는 시드와 같은 규칙(팀원 E-사번, 팀장 L-사번)이다.
     * organization 테이블은 MCP 쪽 스키마라 다른 MCP 테이블처럼 JdbcTemplate으로 넣는다. 비밀번호는 로그인과 같이 평문 저장.
     */
    @Transactional
    public LoginResponse signup(SignupRequest request) {
        if (userRepository.existsByEmployeeNo(request.employeeNo())) {
            throw new BusinessException(ErrorCode.CONFLICT, DUPLICATE_EMPLOYEE_NO);
        }
        UserRole role = request.role() == null ? UserRole.MEMBER : CodedEnum.fromCode(UserRole.class, request.role());
        String companyId = companyIdFor(request.companyName().strip());
        String teamId = request.teamName() == null || request.teamName().isBlank()
                ? null : teamIdFor(companyId, request.teamName().strip());

        String userId = (role == UserRole.TEAM_LEAD ? LEAD_ID_PREFIX : MEMBER_ID_PREFIX) + request.employeeNo();
        try {
            jdbc.update("INSERT INTO users (user_id, employee_no, password, company_id, team_id, name, role) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    userId, request.employeeNo(), request.password(), companyId, teamId, request.name().strip(),
                    role.getCode());
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

    /** 같은 이름(대소문자 무시) 회사가 있으면 그 ID, 없으면 새로 만든다. 같은 이름 동시 가입은 advisory lock으로 하나만 만든다. */
    private String companyIdFor(String name) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "company:" + name.toLowerCase(Locale.ROOT));
        List<String> found = jdbc.queryForList(
                "SELECT company_id FROM companies WHERE lower(name) = lower(?) ORDER BY company_id LIMIT 1", String.class, name);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        String companyId = "C-" + newId();
        jdbc.update("INSERT INTO companies (company_id, name) VALUES (?, ?)", companyId, name);
        return companyId;
    }

    private String teamIdFor(String companyId, String name) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "team:" + companyId + ":" + name.toLowerCase(Locale.ROOT));
        List<String> found = jdbc.queryForList(
                "SELECT team_id FROM teams WHERE company_id = ? AND lower(name) = lower(?) ORDER BY team_id LIMIT 1",
                String.class, companyId, name);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        String teamId = "T-" + newId();
        jdbc.update("INSERT INTO teams (team_id, company_id, name) VALUES (?, ?, ?)", teamId, companyId, name);
        return teamId;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
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
