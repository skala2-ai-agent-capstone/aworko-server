package com.aworko.server.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.global.security.JwtProvider;
import com.aworko.server.organization.UserRole;
import com.aworko.server.support.FakeTeamEvaluations;
import com.aworko.server.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팀 평가 요청·목록·상세를 실제 Postgres(MCP 스키마) 위에서 확인한다. MCP 요청 만들기는 대역(FakeTeamEvaluations),
 * 팀 Agent의 리포트 저장은 team_evaluations를 직접 바꿔 흉내 낸다. 아웃박스 릴레이는 끈다.
 */
@SpringBootTest(properties = "app.messaging.outbox.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeTeamEvaluations.class})
@Sql("/db/seed_minimal.sql")
@Transactional
class TeamEvaluationIntegrationTest {

    static final String COMPANY = "C-LGCNS-SALES";
    static final String BODY = "{\"from\":\"2026-10-19\",\"to\":\"2026-10-25\"}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper objectMapper;
    @Autowired FakeTeamEvaluations.Recorder mcp;
    @Autowired TeamEvaluationService service;

    String member;
    String lead;
    String otherLead;

    @BeforeEach
    void setUp() {
        mcp.calls.clear();
        jdbc.update("INSERT INTO teams VALUES ('T-SALES-02', ?, '영업2팀') ON CONFLICT DO NOTHING", COMPANY);
        jdbc.update("INSERT INTO users (user_id, company_id, team_id, name, role, employee_no, password) "
                + "VALUES ('L-3002', ?, 'T-SALES-02', '다른 팀장', 'team_lead', 3002, '1234') ON CONFLICT DO NOTHING", COMPANY);
        member = token("E-1021", "T-SALES-01", UserRole.MEMBER);
        lead = token("L-3001", "T-SALES-01", UserRole.TEAM_LEAD);
        otherLead = token("L-3002", "T-SALES-02", UserRole.TEAM_LEAD);
    }

    @Test
    void leadRequestCreatesMcpRequestAndQueuesTeamEvaluate() throws Exception {
        String response = mvc.perform(auth(post("/api/teams/T-SALES-01/evaluations"), lead)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("대기"))
                .andExpect(jsonPath("$.data.period_from").value("2026-10-19"))
                .andExpect(jsonPath("$.data.period_to").value("2026-10-25"))
                // 기간에 확정 평가가 없으니 공개할 유형도 없다 → 보류
                .andExpect(jsonPath("$.data.release_approved").value(false))
                .andExpect(jsonPath("$.data.unapproved_work_types.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String requestId = objectMapper.readTree(response).path("data").path("request_id").asText();

        assertThat(mcp.calls).containsExactly(new FakeTeamEvaluations.Call(COMPANY, "T-SALES-01",
                LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-26"), false, requestId, "L-3001"));
        assertThat(jdbc.queryForMap("SELECT type, payload->>'request_id' AS request_id, payload->>'company_id' AS company "
                        + "FROM outbox_events WHERE aggregate_id = ?", requestId))
                .containsEntry("type", "TEAM_EVALUATE").containsEntry("request_id", requestId)
                .containsEntry("company", COMPANY);

        mvc.perform(auth(get("/api/teams/T-SALES-01/evaluations"), lead))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].request_id").value(requestId))
                .andExpect(jsonPath("$.data[0].status").value("대기"))
                .andExpect(jsonPath("$.data[0].unapproved_work_types").doesNotExist());
        mvc.perform(auth(get("/api/teams/T-SALES-01/evaluations/" + requestId), lead))
                .andExpect(jsonPath("$.data.evaluation.status").value("대기"))
                .andExpect(jsonPath("$.data.report").doesNotExist());

        // 팀 Agent가 MCP save_team_evaluation_report로 저장하면 완료
        jdbc.update("UPDATE team_evaluations SET status = '완료', report = ?::jsonb, report_sha256 = ?, revision = 1, "
                        + "saved_at = now() WHERE request_id = ?::uuid",
                "{\"status\":\"withheld\",\"summary\":\"공개 승인 전이라 수치를 보류했습니다\"}", "1".repeat(64), requestId);
        mvc.perform(auth(get("/api/teams/T-SALES-01/evaluations/" + requestId), lead))
                .andExpect(jsonPath("$.data.evaluation.status").value("완료"))
                .andExpect(jsonPath("$.data.evaluation.report_status").value("withheld"))
                .andExpect(jsonPath("$.data.evaluation.completed_at").isNotEmpty())
                .andExpect(jsonPath("$.data.report.summary").value("공개 승인 전이라 수치를 보류했습니다"));
    }

    @Test
    void onlyTheTeamsLeadCanRequestOrRead() throws Exception {
        mvc.perform(auth(post("/api/teams/T-SALES-01/evaluations"), member)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/teams/T-SALES-01/evaluations"), otherLead)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/teams/T-SALES-01/evaluations"), otherLead)).andExpect(status().isForbidden());
        assertThat(mcp.calls).isEmpty();

        String requestId = insertRequest("T-SALES-02", "now()");
        // 다른 팀 요청 ID나 UUID가 아닌 값은 없는 것으로 본다
        mvc.perform(auth(get("/api/teams/T-SALES-01/evaluations/" + requestId), lead)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/teams/T-SALES-01/evaluations/not-a-uuid"), lead)).andExpect(status().isNotFound());
    }

    @Test
    void invalidPeriodAndMcpRejectionAreReportedWithoutQueueMessage() throws Exception {
        mvc.perform(auth(post("/api/teams/T-SALES-01/evaluations"), lead).contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"2026-10-25\",\"to\":\"2026-10-19\"}")).andExpect(status().isBadRequest());
        mcp.rejectNext("VALIDATION_ERROR", "평가 기준 항목을 공통 항목에 맞출 수 없습니다");
        mvc.perform(auth(post("/api/teams/T-SALES-01/evaluations"), lead)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("평가 기준 항목을 공통 항목에 맞출 수 없습니다"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE type = 'TEAM_EVALUATE'", Integer.class))
                .isZero();
    }

    @Test
    void deadLetterAndStaleRequestsShowAsFailed() throws Exception {
        String failed = insertRequest("T-SALES-01", "now()");
        String stale = insertRequest("T-SALES-01", "now() - interval '1 hour'");

        service.fail(objectMapper.readTree("{\"type\":\"TEAM_EVALUATE\",\"request_id\":\"" + failed + "\"}"),
                "LLM_UNAVAILABLE");
        // 형식이 깨졌거나 모르는 요청은 기록하지 않는다
        service.fail(objectMapper.readTree("{\"request_id\":\"nope\"}"), "INVALID_EVENT");
        service.fail(objectMapper.readTree("{\"request_id\":\"" + UUID.randomUUID() + "\"}"), "INVALID_EVENT");

        mvc.perform(auth(get("/api/teams/T-SALES-01/evaluations/" + failed), lead))
                .andExpect(jsonPath("$.data.evaluation.status").value("실패"))
                .andExpect(jsonPath("$.data.evaluation.error").value("LLM_UNAVAILABLE"));
        mvc.perform(auth(get("/api/teams/T-SALES-01/evaluations/" + stale), lead))
                .andExpect(jsonPath("$.data.evaluation.status").value("실패"))
                .andExpect(jsonPath("$.data.evaluation.error").value("TIMED_OUT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team_evaluation_failures", Integer.class)).isEqualTo(1);
    }

    /** MCP가 만든 대기 요청 행 */
    private String insertRequest(String teamId, String requestedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO team_evaluations (request_id, snapshot_id, company_id, team_id, period_start, period_end, "
                + "requested_at, release_approved, min_members, snapshot, snapshot_sha256, source_evaluation_ids) "
                + "VALUES (?::uuid, ?::uuid, ?, ?, '2026-10-19', '2026-10-26', " + requestedAt
                + ", false, 5, '{}'::jsonb, ?, '{}')", id, UUID.randomUUID().toString(), COMPANY, teamId, "0".repeat(64));
        return id;
    }

    private String token(String userId, String teamId, UserRole role) {
        return jwt.issue(new AuthPrincipal(userId, COMPANY, teamId, role)).accessToken();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }
}
