package com.aworko.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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

    @Test
    void loginWithEmployeeNoIssuesJwtAndMeReturnsUser() throws Exception {
        String token = login(1021, "1234");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value("E-1021"))
                .andExpect(jsonPath("$.data.employee_no").value(1021))
                .andExpect(jsonPath("$.data.company_id").value("C-LGCNS-SALES"))
                .andExpect(jsonPath("$.data.team_id").value("T-SALES-01"))
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
    void legacySeedRoleIsReadAsMember() throws Exception {
        mockMvc.perform(loginRequest(4001, "1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("member"));
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
}
