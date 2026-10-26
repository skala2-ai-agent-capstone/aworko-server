package com.aworko.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.global.security.JwtProperties;
import com.aworko.server.global.security.JwtProvider;
import com.aworko.server.organization.UserRole;
import com.aworko.server.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql("/db/seed_minimal.sql")
class AuthIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtProperties jwtProperties;
    @Autowired JdbcTemplate jdbc;

    /** 가입 테스트는 7000번대 사번과 「가입테스트」로 시작하는 회사·팀만 쓴다. 다른 테스트의 팀원 수가 바뀌지 않게 지운다 */
    @AfterEach
    void deleteSignedUpUsers() {
        jdbc.update("DELETE FROM users WHERE employee_no BETWEEN 7000 AND 7999");
        jdbc.update("DELETE FROM teams WHERE name LIKE '가입테스트%' OR company_id IN "
                + "(SELECT company_id FROM companies WHERE name LIKE '가입테스트%')");
        jdbc.update("DELETE FROM companies WHERE name LIKE '가입테스트%'");
    }

    @Test
    void loginWithEmployeeNoIssuesJwtAndMeReturnsUser() throws Exception {
        String token = login(1021, "1234");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value("E-1021"))
                .andExpect(jsonPath("$.data.employee_no").value(1021))
                .andExpect(jsonPath("$.data.company_id").value("C-LGCNS-SALES"))
                .andExpect(jsonPath("$.data.team_id").value("T-SALES-01"))
                .andExpect(jsonPath("$.data.team_name").value("영업1팀"))
                .andExpect(jsonPath("$.data.company_name").value("테스트 회사"))
                .andExpect(jsonPath("$.data.avatar_initial").value("가"))
                .andExpect(jsonPath("$.data.role").value("member"));
    }

    @Test
    void loginResponseHasBearerTokenAndUserButNoPassword() throws Exception {
        mockMvc.perform(loginRequest(3001, "1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token_type").value("Bearer"))
                .andExpect(jsonPath("$.data.expires_in").value(8 * 3600))
                .andExpect(jsonPath("$.data.user.role").value("team_lead"))
                .andExpect(jsonPath("$.data.user.password").doesNotExist());
    }

    @Test
    void userWithoutTeamHasNoTeamName() throws Exception {
        mockMvc.perform(loginRequest(4001, "1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("member"))
                .andExpect(jsonPath("$.data.user.company_name").value("테스트 회사"))
                .andExpect(jsonPath("$.data.user.team_name").doesNotExist());
    }

    @Test
    void wrongPasswordAndUnknownEmployeeNoReturnSame401() throws Exception {
        Object[][] cases = {{1021, "wrong"}, {9999, "1234"}};
        for (Object[] c : cases) {
            mockMvc.perform(loginRequest((Integer) c[0], (String) c[1]))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.error.message").value("사번 또는 비밀번호가 올바르지 않습니다"));
        }
    }

    @Test
    void missingOrInvalidFieldsReturn400() throws Exception {
        for (String body : new String[]{"{\"employee_no\":1021}", "{\"employee_no\":\"abc\",\"password\":\"1234\"}"}) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void meWithoutTamperedOrExpiredTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());

        String token = login(1021, "1234");
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token + "x"))
                .andExpect(status().isUnauthorized());

        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(9)), ZoneId.systemDefault());
        String expired = new JwtProvider(jwtProperties, past)
                .issue(new AuthPrincipal("E-1021", "C-LGCNS-SALES", "T-SALES-01", UserRole.MEMBER))
                .accessToken();
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() throws Exception {
        var other = new JwtProperties("another-secret-that-is-at-least-32-bytes!!", Duration.ofHours(1),
                jwtProperties.issuer());
        String forged = new JwtProvider(other, Clock.systemDefaultZone())
                .issue(new AuthPrincipal("E-1021", "C-LGCNS-SALES", "T-SALES-01", UserRole.TEAM_LEAD))
                .accessToken();
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupJoinsExistingCompanyAndTeamByNameAndLogsIn() throws Exception {
        Map<String, Object> body = signupBody(7001, "password1");
        body.put("name", "  새 직원  ");
        body.put("company_name", " 테스트 회사 ");
        body.put("team_name", "영업1팀");

        String response = mockMvc.perform(signupRequest(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.token_type").value("Bearer"))
                .andExpect(jsonPath("$.data.user.user_id").value("E-7001"))
                .andExpect(jsonPath("$.data.user.employee_no").value(7001))
                .andExpect(jsonPath("$.data.user.name").value("새 직원"))
                .andExpect(jsonPath("$.data.user.role").value("member"))
                .andExpect(jsonPath("$.data.user.company_id").value("C-LGCNS-SALES"))
                .andExpect(jsonPath("$.data.user.team_id").value("T-SALES-01"))
                .andExpect(jsonPath("$.data.user.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(response).path("data").path("access_token").asText();
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value("E-7001"));
        assertThat(login(7001, "password1")).isNotBlank();
    }

    @Test
    void signupAsTeamLeadGetsLeadRole() throws Exception {
        Map<String, Object> body = signupBody(7002, "password1");
        body.put("role", "team_lead");
        String response = mockMvc.perform(signupRequest(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.user_id").value("L-7002"))
                .andExpect(jsonPath("$.data.user.role").value("team_lead"))
                .andReturn().getResponse().getContentAsString();

        // 팀장 전용 API(팀 업무 목록)를 쓸 수 있다
        String token = objectMapper.readTree(response).path("data").path("access_token").asText();
        mockMvc.perform(get("/api/works").param("scope", "team").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void signupWithNewCompanyAndTeamCreatesThemOnceAndOthersJoin() throws Exception {
        Map<String, Object> first = signupBody(7003, "password1");
        first.put("role", "team_lead");
        first.put("company_name", "가입테스트 새 회사");
        first.put("team_name", "가입테스트 새 팀");
        String created = mockMvc.perform(signupRequest(first))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.company_name").value("가입테스트 새 회사"))
                .andExpect(jsonPath("$.data.user.team_name").value("가입테스트 새 팀"))
                .andReturn().getResponse().getContentAsString();
        JsonNode user = objectMapper.readTree(created).path("data").path("user");
        assertThat(user.path("company_id").asText()).startsWith("C-").isNotEqualTo("C-LGCNS-SALES");
        assertThat(user.path("team_id").asText()).startsWith("T-");

        // 대소문자·앞뒤 공백이 달라도 같은 회사·팀에 들어간다
        Map<String, Object> second = signupBody(7004, "password1");
        second.put("company_name", "  가입테스트 새 회사 ");
        second.put("team_name", "가입테스트 새 팀");
        mockMvc.perform(signupRequest(second))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.company_id").value(user.path("company_id").asText()))
                .andExpect(jsonPath("$.data.user.team_id").value(user.path("team_id").asText()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM companies WHERE name = '가입테스트 새 회사'", Integer.class))
                .isOne();

        // 가입 화면 목록에도 새 회사·팀이 나온다
        mockMvc.perform(get("/api/auth/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name == '가입테스트 새 회사')].teams[0].name").value("가입테스트 새 팀"));
    }

    @Test
    void signupWithoutTeamLeavesTeamEmpty() throws Exception {
        for (Object teamName : new Object[]{null, " "}) {
            int employeeNo = teamName == null ? 7005 : 7006;
            Map<String, Object> body = signupBody(employeeNo, "password1");
            body.put("team_name", teamName);
            mockMvc.perform(signupRequest(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.user.team_id").doesNotExist())
                    .andExpect(jsonPath("$.data.user.company_name").value("테스트 회사"));
        }
    }

    @Test
    void signupWithTakenEmployeeNoReturns409() throws Exception {
        mockMvc.perform(signupRequest(signupBody(7007, "password1"))).andExpect(status().isCreated());

        for (int employeeNo : new int[]{1021, 7007}) {
            Map<String, Object> body = signupBody(employeeNo, "password2");
            body.put("company_name", "가입테스트 중복 회사");
            mockMvc.perform(signupRequest(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.error.message").value("이미 가입된 사번입니다"));
        }
        // 기존 계정은 그대로이고, 실패한 가입은 회사를 만들지 않는다
        assertThat(login(1021, "1234")).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM companies WHERE name = '가입테스트 중복 회사'", Integer.class))
                .isZero();
    }

    @Test
    void signupWithInvalidFieldsReturns400() throws Exception {
        Map<String, Object> shortPassword = signupBody(7008, "1234");
        Map<String, Object> noName = signupBody(7008, "password1");
        noName.remove("name");
        Map<String, Object> noCompany = signupBody(7008, "password1");
        noCompany.remove("company_name");
        Map<String, Object> unknownRole = signupBody(7008, "password1");
        unknownRole.put("role", "admin");
        Map<String, Object> zeroEmployeeNo = signupBody(0, "password1");

        for (Map<String, Object> body : List.of(shortPassword, noName, noCompany, unknownRole, zeroEmployeeNo)) {
            mockMvc.perform(signupRequest(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE employee_no = 7008", Integer.class)).isZero();
    }

    @Test
    void companyOptionsArePublicAndListTeams() throws Exception {
        mockMvc.perform(get("/api/auth/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.company_id == 'C-LGCNS-SALES')].name").value("테스트 회사"))
                .andExpect(jsonPath("$.data[?(@.company_id == 'C-LGCNS-SALES')].teams[*].team_id", hasItem("T-SALES-01")));
    }

    private String login(int employeeNo, String password) throws Exception {
        String body = mockMvc.perform(loginRequest(employeeNo, password))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        String token = json.path("data").path("access_token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private RequestBuilder loginRequest(Integer employeeNo, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(employeeNo, password));
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static Map<String, Object> signupBody(int employeeNo, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employee_no", employeeNo);
        body.put("password", password);
        body.put("name", "가입 직원");
        body.put("company_name", "테스트 회사");
        body.put("team_name", "영업1팀");
        return body;
    }

    private RequestBuilder signupRequest(Map<String, Object> body) throws Exception {
        return post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }
}
