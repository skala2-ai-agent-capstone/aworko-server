package com.aworko.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.global.security.JwtProvider;
import com.aworko.server.organization.UserRole;
import com.aworko.server.support.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
 * 평가·검토·실시간 피드백·팀 대시보드 API를 실제 Postgres(목표 스키마) 위에서 확인한다.
 * 테스트마다 트랜잭션을 롤백하므로 서로 영향을 주지 않는다. 아웃박스 릴레이는 끈다(메시지는 outbox_events로 확인).
 */
@SpringBootTest(properties = "app.messaging.outbox.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql("/db/seed_minimal.sql")
@Transactional
class ApiFlowIntegrationTest {

    static final String COMPANY = "C-LGCNS-SALES";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired EntityManager entityManager;

    String member;      // E-1021, T-SALES-01 직원
    String lead;        // L-3001, T-SALES-01 팀장
    String otherLead;   // L-3002, T-SALES-02 팀장

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO teams VALUES ('T-SALES-02', ?, '영업2팀') ON CONFLICT DO NOTHING", COMPANY);
        jdbc.update("INSERT INTO users (user_id, company_id, team_id, name, role, employee_no, password) "
                + "VALUES ('L-3002', ?, 'T-SALES-02', '다른 팀장', 'team_lead', 3002, '1234') ON CONFLICT DO NOTHING",
                COMPANY);
        member = token("E-1021", "T-SALES-01", UserRole.MEMBER);
        lead = token("L-3001", "T-SALES-01", UserRole.TEAM_LEAD);
        otherLead = token("L-3002", "T-SALES-02", UserRole.TEAM_LEAD);
    }

    @Nested
    class WorkEvents {

        @Test
        void completingWorkCreatesEvaluationOnceAndQueuesEvaluate() throws Exception {
            String body = "{\"task_id\":\"W-TEST-QT-01\",\"deliverable_version\":\"v2\"}";
            mvc.perform(auth(post("/api/work-events"), member).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.evaluation_id").value("EVAL-TEST-QT-01-v2"))
                    .andExpect(jsonPath("$.data.created").value(true))
                    .andExpect(jsonPath("$.data.status").value("대기"));
            mvc.perform(auth(post("/api/work-events"), member).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.evaluation_id").value("EVAL-TEST-QT-01-v2"))
                    .andExpect(jsonPath("$.data.created").value(false));

            assertThat(count("SELECT count(*) FROM evaluations WHERE work_id = 'W-TEST-QT-01'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM outbox_events WHERE type = 'EVALUATE' "
                    + "AND aggregate_id = 'EVAL-TEST-QT-01-v2'")).isEqualTo(1);
        }

        @Test
        void otherUsersWorkUnknownVersionAndUntypedWorkAreRejected() throws Exception {
            mvc.perform(auth(post("/api/work-events"), lead).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"task_id\":\"W-TEST-QT-01\",\"deliverable_version\":\"v2\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(auth(post("/api/work-events"), member).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"task_id\":\"W-TEST-QT-01\",\"deliverable_version\":\"v9\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(auth(post("/api/work-events"), member).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"task_id\":\"W-TEST-NEW-01\",\"deliverable_version\":\"v1\"}"))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    class Evaluations {

        @BeforeEach
        void evaluationAwaitingConfirmation() {
            insertEvaluation("EVAL-F1", "확인 대기");
            jdbc.update("INSERT INTO evaluation_drafts (evaluation_id, revision, items) VALUES ('EVAL-F1', 1, ?::jsonb)",
                    "[{\"item_id\":\"q.1\",\"item\":\"문제 정의\",\"status\":\"채점\",\"score\":2,\"evidence_ids\":[\"W:M-1\"]},"
                            + "{\"item_id\":\"q.3\",\"item\":\"결과 검증\",\"status\":\"미관측\",\"score\":null}]");
            jdbc.update("INSERT INTO evaluation_coaching (evaluation_id, revision, items) VALUES ('EVAL-F1', 1, ?::jsonb)",
                    "[{\"item_id\":\"q.3\",\"kind\":\"보완점\",\"text\":\"원문과 대조한 기록을 남기세요\"}]");
            jdbc.update("INSERT INTO progress_log (evaluation_id, stage) VALUES ('EVAL-F1', '평가 중')");
        }

        @Test
        void ownerSeesReportWithUnobservedSeparated() throws Exception {
            mvc.perform(auth(get("/api/evaluations/EVAL-F1"), member))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.evaluation.display_status").value("확인 대기"))
                    .andExpect(jsonPath("$.data.evaluation.work_type").value("quote"))
                    .andExpect(jsonPath("$.data.report.total_score").value(2))
                    .andExpect(jsonPath("$.data.report.max_score").value(2))
                    .andExpect(jsonPath("$.data.report.unobserved_count").value(1))
                    .andExpect(jsonPath("$.data.report.items[1].score_label").value("미관측"))
                    .andExpect(jsonPath("$.data.report.items[1].coaching[0].kind").value("보완점"))
                    .andExpect(jsonPath("$.data.progress[0].stage").value("평가 중"));
            mvc.perform(auth(get("/api/evaluations"), member))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.evaluation_id == 'EVAL-F1')]").exists());
        }

        @Test
        void teamLeadWithoutAssignedReviewCannotSeeDetail() throws Exception {
            mvc.perform(auth(get("/api/evaluations/EVAL-F1"), lead)).andExpect(status().isForbidden());
            mvc.perform(auth(get("/api/evaluations/EVAL-NONE"), member)).andExpect(status().isNotFound());
        }

        @Test
        void confirmMovesToConfirmedOnceRecordsActionAndQueuesResume() throws Exception {
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/confirm"), member))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("확정"));
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/confirm"), member))
                    .andExpect(status().isConflict());

            assertThat(count("SELECT count(*) FROM human_actions WHERE action = 'confirm' "
                    + "AND payload->>'evaluation_id' = 'EVAL-F1'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM outbox_events WHERE type = 'RESUME' AND aggregate_id = 'EVAL-F1' "
                    + "AND payload->>'action' = 'confirm'")).isEqualTo(1);
        }

        @Test
        void appealIsRecordedAndStatusStaysUntilAgentRequestsReview() throws Exception {
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/appeals"), member).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"target_items\":[\"q.3\"],\"comment\":\"검토 기록이 있습니다\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.status").value("확인 대기"));

            assertThat(jdbc.queryForObject("SELECT payload->>'comment' FROM human_actions WHERE action = 'appeal' "
                    + "AND payload->>'evaluation_id' = 'EVAL-F1'", String.class)).isEqualTo("검토 기록이 있습니다");
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/appeals"), member).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"target_items\":[],\"comment\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void retryIsForTeamLeadOnFailedEvaluations() throws Exception {
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/retry"), lead)).andExpect(status().isConflict());
            setStatus("EVAL-F1", "처리 실패");

            mvc.perform(auth(post("/api/evaluations/EVAL-F1/retry"), member)).andExpect(status().isForbidden());
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/retry"), otherLead)).andExpect(status().isForbidden());
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/retry"), lead))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.status").value("대기"));
            assertThat(count("SELECT count(*) FROM outbox_events WHERE type = 'EVALUATE' AND aggregate_id = 'EVAL-F1'"))
                    .isEqualTo(1);
        }
    }

    @Nested
    class Reviews {

        @BeforeEach
        void evaluationAwaitingReview() {
            insertEvaluation("EVAL-R1", "검토 대기");
            jdbc.update("INSERT INTO review_requests (review_request_id, evaluation_id, target_items, reason, "
                    + "review_memo, reviewer_id) VALUES ('RVQ-1', 'EVAL-R1', '[\"q.3\"]', '이의 제기', '{}', 'L-3001')");
            jdbc.update("INSERT INTO human_actions (action_id, work_id, user_id, action, at, payload, payload_masked) "
                    + "VALUES ('HA-T1', 'W-TEST-QT-01', 'E-1021', 'appeal', now(), "
                    + "'{\"evaluation_id\":\"EVAL-R1\",\"comment\":\"근거 재확인 요청\"}', '{}')");
        }

        @Test
        void assignedLeadSeesListDetailAndEvaluation() throws Exception {
            mvc.perform(auth(get("/api/reviews"), lead))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].review_request_id").value("RVQ-1"))
                    .andExpect(jsonPath("$.data[0].display_status").value("검토 중"));
            mvc.perform(auth(get("/api/reviews/RVQ-1"), lead))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.appeal.comment").value("근거 재확인 요청"))
                    .andExpect(jsonPath("$.data.evaluation.evaluation.evaluation_id").value("EVAL-R1"));
            // 검토가 배정되면 평가 상세도 열람 가능
            mvc.perform(auth(get("/api/evaluations/EVAL-R1"), lead)).andExpect(status().isOk());
        }

        @Test
        void membersAndUnassignedLeadsCannotReview() throws Exception {
            mvc.perform(auth(get("/api/reviews"), member)).andExpect(status().isForbidden());
            mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), otherLead).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"keep\"}")).andExpect(status().isForbidden());
        }

        @Test
        void keepConfirmsOverrideRequeuesHoldParks() throws Exception {
            mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), lead).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"keep\",\"note\":\"근거 확인\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("확정"));
            mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), lead).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"keep\"}")).andExpect(status().isConflict());

            setStatus("EVAL-R1", "검토 대기");
            mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), lead).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"override\",\"new_scores\":{\"q.3\":2}}"))
                    .andExpect(jsonPath("$.data.status").value("대기"));
            assertThat(count("SELECT count(*) FROM outbox_events WHERE type = 'RESUME' "
                    + "AND payload->>'action' = 'review_decision'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM human_actions WHERE action = 'review_decision' "
                    + "AND payload->'new_scores'->>'q.3' = '2'")).isEqualTo(1);

            setStatus("EVAL-R1", "검토 대기");
            mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), lead).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"hold\"}"))
                    .andExpect(jsonPath("$.data.status").value("자료 보류"));
        }
    }

    @Nested
    class LiveEvaluations {

        @BeforeEach
        void conversation() {
            for (int seq = 1; seq <= 7; seq++) {
                jdbc.update("INSERT INTO messages (message_id, work_id, seq, role, content, content_masked, created_at) "
                                + "VALUES (?, 'W-TEST-QT-01', ?, ?, 'x', 'x', now())",
                        "W-TEST-QT-01-M" + seq, seq, seq % 2 == 1 ? "user" : "assistant");
            }
        }

        @Test
        void requestFixesLastFiveMessagesQueuesAndLimitsToOncePerFiveMinutes() throws Exception {
            String body = "{\"work_id\":\"W-TEST-QT-01\"}";
            String response = mvc.perform(auth(post("/api/live-evaluations"), member)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.status").value("대기"))
                    .andExpect(jsonPath("$.data.from_seq").value(3))
                    .andExpect(jsonPath("$.data.to_seq").value(7))
                    .andReturn().getResponse().getContentAsString();
            String liveId = response.replaceAll(".*\"live_eval_id\":\"([^\"]+)\".*", "$1");
            assertThat(count("SELECT count(*) FROM outbox_events WHERE type = 'LIVE_EVALUATE'")).isEqualTo(1);

            mvc.perform(auth(post("/api/live-evaluations"), member).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
                    .andExpect(jsonPath("$.error.retry_after_seconds").isNumber());

            mvc.perform(auth(get("/api/live-evaluations/" + liveId), member))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.live_eval_id").value(liveId));
            mvc.perform(auth(get("/api/live-evaluations/" + liveId), lead)).andExpect(status().isForbidden());
        }

        @Test
        void workWithoutConversationOrOthersWorkIsRejected() throws Exception {
            mvc.perform(auth(post("/api/live-evaluations"), member).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"work_id\":\"W-TEST-NEW-01\"}")).andExpect(status().isConflict());
            mvc.perform(auth(post("/api/live-evaluations"), lead).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"work_id\":\"W-TEST-QT-01\"}")).andExpect(status().isForbidden());
        }
    }

    @Nested
    class TeamDashboards {

        @Test
        void leadSeesOwnTeamWithSmallTypesRestrictedAndCannotApproveThem() throws Exception {
            insertEvaluation("EVAL-D1", "확정");

            mvc.perform(auth(get("/api/teams/T-SALES-01/dashboard"), lead))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.team_name").value("영업1팀"))
                    .andExpect(jsonPath("$.data.min_aggregation_size").value(5))
                    .andExpect(jsonPath("$.data.stats.confirmed_count").value(1))
                    .andExpect(jsonPath("$.data.work_types[0].work_type").value("quote"))
                    .andExpect(jsonPath("$.data.work_types[0].restricted").value(true))
                    .andExpect(jsonPath("$.data.daily_trend.length()").value(7));

            String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
            mvc.perform(auth(post("/api/teams/T-SALES-01/dashboard/approve"), lead)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"work_type\":\"quote\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        void membersAndOtherTeamLeadsCannotSeeDashboard() throws Exception {
            mvc.perform(auth(get("/api/teams/T-SALES-01/dashboard"), member)).andExpect(status().isForbidden());
            mvc.perform(auth(get("/api/teams/T-SALES-01/dashboard"), otherLead)).andExpect(status().isForbidden());
            mvc.perform(auth(get("/api/teams/T-NOPE/dashboard"), lead)).andExpect(status().isNotFound());
        }
    }

    private void insertEvaluation(String id, String status) {
        jdbc.update("INSERT INTO evaluations (evaluation_id, work_id, company_id, artifact_id, artifact_version, "
                        + "rubric_version, status, source_key) VALUES (?, 'W-TEST-QT-01', ?, 'ART-TEST-QT-01', 'v2', 'v2', ?, ?)",
                id, COMPANY, status, id);
    }

    /** JDBC로 상태를 바꾼 뒤 영속성 컨텍스트를 비워, 다음 요청이 DB의 새 상태를 읽게 한다. */
    private void setStatus(String evaluationId, String status) {
        entityManager.flush();   // 앞 요청의 엔티티 변경을 먼저 반영해야 아래 UPDATE를 덮어쓰지 않는다
        entityManager.clear();
        jdbc.update("UPDATE evaluations SET status = ? WHERE evaluation_id = ?", status, evaluationId);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private String token(String userId, String teamId, UserRole role) {
        return jwt.issue(new AuthPrincipal(userId, COMPANY, teamId, role)).accessToken();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }
}
