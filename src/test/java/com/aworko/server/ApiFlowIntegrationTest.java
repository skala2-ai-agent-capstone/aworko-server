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
import com.aworko.server.support.FakeLiveRequests;
import com.aworko.server.support.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평가·검토·실시간 피드백·팀 대시보드 API를 실제 Postgres(MCP 스키마) 위에서 확인한다.
 * 테스트마다 트랜잭션을 롤백하므로 서로 영향을 주지 않는다. 아웃박스 릴레이는 끈다(메시지는 outbox_events로 확인).
 */
@SpringBootTest(properties = "app.messaging.outbox.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeLiveRequests.class})
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
        void completingAgainIsRejectedWhileTheLatestEvaluationIsActiveOrDone() throws Exception {
            insertEvaluation("EVAL-OLD", "대기");
            for (String current : List.of("대기", "코칭 생성 중", "확인 대기", "검토 대기", "확정")) {
                setStatus("EVAL-OLD", current);
                complete("v2").andExpect(status().isConflict());
            }
            assertThat(count("SELECT count(*) FROM evaluations WHERE work_id = 'W-TEST-QT-01'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM outbox_events WHERE type = 'EVALUATE'")).isZero();
        }

        @Test
        void completingAgainIsAcceptedAfterFailure() throws Exception {
            insertEvaluation("EVAL-OLD", "처리 실패");
            complete("v2").andExpect(status().isAccepted()).andExpect(jsonPath("$.data.created").value(true));
            // 새 평가가 진행 중이라 이제는 막힌다(같은 버전 재시도는 기존 평가 건을 돌려준다)
            complete("v2").andExpect(status().isAccepted()).andExpect(jsonPath("$.data.created").value(false));
        }

        @Test
        void completingAgainIsAcceptedWhenMaterialsWereOnHold() throws Exception {
            insertEvaluation("EVAL-OLD", "자료 보류");
            complete("v2").andExpect(status().isAccepted()).andExpect(jsonPath("$.data.created").value(true));
        }

        private ResultActions complete(String version) throws Exception {
            return mvc.perform(auth(post("/api/work-events"), member).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"task_id\":\"W-TEST-QT-01\",\"deliverable_version\":\"" + version + "\"}"));
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
        void confirmMovesToConfirmedOnceRecordsActionWithoutQueueMessage() throws Exception {
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/confirm"), member))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("확정"));
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/confirm"), member))
                    .andExpect(status().isConflict());

            assertThat(count("SELECT count(*) FROM human_actions WHERE action = 'confirm' "
                    + "AND payload->>'evaluation_id' = 'EVAL-F1'")).isEqualTo(1);
            // 확정은 백엔드에서 끝난다. Agent 큐로 보내는 메시지(RESUME)는 없다
            assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = 'EVAL-F1'")).isZero();
        }

        @Test
        void appealCreatesReviewForTeamLeadAndAwaitsReview() throws Exception {
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/appeals"), member).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"target_items\":[],\"comment\":\"\"}"))
                    .andExpect(status().isBadRequest());
            // 항목 이름으로 골라도 item_id로 바꿔 저장한다
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/appeals"), member).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"target_items\":[\"결과 검증\"],\"comment\":\"검토 기록이 있습니다\","
                                    + "\"cited_evidence\":[\"W:M-1\"]}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.status").value("검토 대기"));

            assertThat(jdbc.queryForMap("SELECT target_items::text AS targets, reviewer_id, reason, "
                    + "review_memo->'cited_evidence'->>0 AS cited FROM review_requests WHERE evaluation_id = 'EVAL-F1'"))
                    .containsEntry("targets", "[\"q.3\"]").containsEntry("reviewer_id", "L-3001")
                    .containsEntry("reason", "직원 이의 제기").containsEntry("cited", "W:M-1");
            entityManager.flush();   // human_actions는 JPA로 저장된다. 아래 JDBC 조회 전에 반영
            assertThat(jdbc.queryForObject("SELECT payload->>'comment' FROM human_actions WHERE action = 'appeal' "
                    + "AND payload->>'evaluation_id' = 'EVAL-F1'", String.class)).isEqualTo("검토 기록이 있습니다");
            assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = 'EVAL-F1'")).isZero();
            // 담당 팀장 목록에 바로 보이고, 같은 평가에 다시 이의를 걸 수는 없다
            mvc.perform(auth(get("/api/reviews"), lead))
                    .andExpect(jsonPath("$.data[?(@.evaluation_id == 'EVAL-F1')].display_status").value("검토 중"));
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/appeals"), member).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"target_items\":[\"q.3\"],\"comment\":\"한 번 더\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        void appealOnUnknownItemIsRejectedAndStatusStays() throws Exception {
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/appeals"), member).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"target_items\":[\"q.9\"],\"comment\":\"없는 항목\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(auth(get("/api/evaluations/EVAL-F1"), member))
                    .andExpect(jsonPath("$.data.evaluation.display_status").value("확인 대기"));
            assertThat(count("SELECT count(*) FROM review_requests WHERE evaluation_id = 'EVAL-F1'")).isZero();
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

        @Test
        void retryOnHoldSendsANewEvaluateMessage() throws Exception {
            setStatus("EVAL-F1", "자료 보류");
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/retry"), lead))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.status").value("대기"));
            assertThat(count("SELECT count(*) FROM outbox_events WHERE type = 'EVALUATE' AND aggregate_id = 'EVAL-F1'"))
                    .isEqualTo(1);
        }

        /** 직원이 새 버전으로 다시 완료한 뒤에는 이전 평가를 재처리하지 않는다(한 업무에 평가가 둘 진행되지 않게) */
        @Test
        void retryOnlyTheLatestEvaluationOfTheWork() throws Exception {
            setStatus("EVAL-F1", "처리 실패");
            insertEvaluation("EVAL-F2", "처리 실패");
            jdbc.update("UPDATE evaluations SET created_at = created_at + interval '1 minute' WHERE evaluation_id = 'EVAL-F2'");

            mvc.perform(auth(post("/api/evaluations/EVAL-F1/retry"), lead)).andExpect(status().isConflict());
            mvc.perform(auth(post("/api/evaluations/EVAL-F2/retry"), lead)).andExpect(status().isAccepted());
        }

        /** 메시지를 잃었거나 Worker가 멈춰 '평가 중'에 남은 건은 app.evaluation.stale-after가 지나면 재처리할 수 있다 */
        @Test
        void retryUnsticksAnEvaluationLeftInProgress() throws Exception {
            setStatus("EVAL-F1", "대기");
            mvc.perform(auth(post("/api/evaluations/EVAL-F1/retry"), lead)).andExpect(status().isConflict());

            entityManager.flush();
            entityManager.clear();
            jdbc.update("UPDATE evaluations SET updated_at = now() - interval '31 minutes' WHERE evaluation_id = 'EVAL-F1'");
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
                    .andExpect(jsonPath("$.data[0].display_status").value("검토 중"))
                    .andExpect(jsonPath("$.data[0].work_type_label").value("견적서 작성"))
                    .andExpect(jsonPath("$.data[0].employee_name").value("가상 직원 A"));
            mvc.perform(auth(get("/api/reviews/RVQ-1"), lead))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.appeal.comment").value("근거 재확인 요청"))
                    .andExpect(jsonPath("$.data.appeal.submitted_by_name").value("가상 직원 A"))
                    .andExpect(jsonPath("$.data.evaluation.evaluation.evaluation_id").value("EVAL-R1"));
            // 직원이 이의를 제기했고 검토가 배정되면 평가 상세도 열람 가능
            mvc.perform(auth(get("/api/evaluations/EVAL-R1"), lead)).andExpect(status().isOk());
        }

        @Test
        void membersAndUnassignedLeadsCannotReview() throws Exception {
            mvc.perform(auth(get("/api/reviews"), member)).andExpect(status().isForbidden());
            mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), otherLead).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"keep\"}")).andExpect(status().isForbidden());
        }

        @Test
        void reviewWithoutEmployeeAppealStaysHiddenFromAssignedLead() throws Exception {
            // Agent 자동 배정('검토 필요' 항목)으로만 생긴 검토. 팀장 본인이 남긴 appeal 기록은 근거가 아니다
            insertEvaluation("EVAL-R2", "검토 대기");
            jdbc.update("INSERT INTO review_requests (review_request_id, evaluation_id, target_items, reason, "
                    + "review_memo, reviewer_id) VALUES ('RVQ-2', 'EVAL-R2', '[\"q.3\"]', '검토 필요', '{}', 'L-3001')");
            jdbc.update("INSERT INTO human_actions (action_id, work_id, user_id, action, at, payload, payload_masked) "
                    + "VALUES ('HA-T2', 'W-TEST-QT-01', 'L-3001', 'appeal', now(), '{\"evaluation_id\":\"EVAL-R2\"}', '{}')");

            mvc.perform(auth(get("/api/reviews"), lead))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].review_request_id").value("RVQ-1"));
            mvc.perform(auth(get("/api/reviews/RVQ-2"), lead)).andExpect(status().isForbidden());
            mvc.perform(auth(get("/api/evaluations/EVAL-R2"), lead)).andExpect(status().isForbidden());
            mvc.perform(auth(post("/api/reviews/RVQ-2/resolve"), lead).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"keep\"}")).andExpect(status().isForbidden());
        }

        @Test
        void keepConfirmsOverrideAppliesLeadScoresHoldParks() throws Exception {
            jdbc.update("INSERT INTO evaluation_drafts (evaluation_id, revision, items) VALUES ('EVAL-R1', 1, ?::jsonb)",
                    "[{\"item_id\":\"q.1\",\"item\":\"문제 정의\",\"status\":\"채점\",\"score\":2,\"evidence_ids\":[\"W:M-1\"]},"
                            + "{\"item_id\":\"q.3\",\"item\":\"결과 검증\",\"status\":\"미관측\",\"score\":null,"
                            + "\"reason\":\"대조 기록 없음\"}]");
            mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), lead).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"keep\",\"note\":\"근거 확인\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("확정"));
            mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), lead).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"keep\"}")).andExpect(status().isConflict());

            // 수정 요청: 점수가 없거나, 범위 밖이거나, 없는 항목이면 거절하고 초안은 그대로다
            setStatus("EVAL-R1", "검토 대기");
            for (String bad : new String[]{"{\"decision\":\"override\"}",
                    "{\"decision\":\"override\",\"new_scores\":{\"q.3\":3}}",
                    "{\"decision\":\"override\",\"new_scores\":{\"q.9\":1}}"}) {
                mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), lead).contentType(MediaType.APPLICATION_JSON)
                        .content(bad)).andExpect(status().isBadRequest());
            }
            assertThat(count("SELECT count(*) FROM evaluation_drafts WHERE evaluation_id = 'EVAL-R1'")).isEqualTo(1);

            // 팀장 점수를 새 초안 판으로 반영하고 직원 본인 확인(확인 대기)으로 돌린다. AI 재평가 메시지는 없다
            mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), lead).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"override\",\"note\":\"승인 요청 기록 확인\",\"new_scores\":{\"q.3\":2}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("확인 대기"));
            assertThat(jdbc.queryForMap("SELECT items->1->>'status' AS status, items->1->>'score' AS score, "
                            + "items->1->>'reason' AS reason, items->1->'review_override'->>'reviewer_id' AS reviewer, "
                            + "items->1->'review_override'->'previous'->>'status' AS previous, "
                            + "items->0->>'score' AS untouched FROM evaluation_drafts "
                            + "WHERE evaluation_id = 'EVAL-R1' AND revision = 2"))
                    .containsEntry("status", "채점").containsEntry("score", "2")
                    .containsEntry("reason", "승인 요청 기록 확인").containsEntry("reviewer", "L-3001")
                    .containsEntry("previous", "미관측").containsEntry("untouched", "2");
            assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = 'EVAL-R1'")).isZero();
            entityManager.flush();   // human_actions는 JPA로 저장된다. 아래 JDBC 조회 전에 반영
            assertThat(count("SELECT count(*) FROM human_actions WHERE action = 'review_decision' "
                    + "AND payload->'new_scores'->>'q.3' = '2' AND payload->>'draft_revision' = '2'")).isEqualTo(1);
            mvc.perform(auth(get("/api/evaluations/EVAL-R1"), member))
                    .andExpect(jsonPath("$.data.draft_revision").value(2))
                    .andExpect(jsonPath("$.data.report.total_score").value(4));

            setStatus("EVAL-R1", "검토 대기");
            mvc.perform(auth(post("/api/reviews/RVQ-1/resolve"), lead).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"hold\"}"))
                    .andExpect(jsonPath("$.data.status").value("자료 보류"));
        }
    }

    @Nested
    class LiveEvaluations {

        /**
         * messages는 llm_exchanges를 펼친 뷰다. 성공 호출 6번(질문+답변) + 실패 호출 1번(질문만) → seq 1~13, 13번은 답 없는 질문.
         * 최근 완료 5쌍은 seq 3~12다(메시지 5개가 아니다).
         */
        @BeforeEach
        void conversation() {
            for (int call = 1; call <= 7; call++) {
                boolean failed = call == 7;
                jdbc.update("INSERT INTO llm_exchanges (work_id, stage, request, response, error) "
                                + "VALUES ('W-TEST-QT-01', 'workspace_chat', ?::jsonb, ?::jsonb, ?)",
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"q" + call + "\"}]}",
                        failed ? null : "{\"choices\":[{\"message\":{\"content\":\"a" + call + "\"}}]}",
                        failed ? "upstream_error" : null);
            }
        }

        @Test
        void requestFixesRecentFivePairsQueuesAndLimitsToOncePerFiveMinutes() throws Exception {
            String body = "{\"work_id\":\"W-TEST-QT-01\"}";
            String response = mvc.perform(auth(post("/api/live-evaluations"), member)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.status").value("대기"))
                    .andExpect(jsonPath("$.data.from_seq").value(3))
                    .andExpect(jsonPath("$.data.to_seq").value(12))
                    .andReturn().getResponse().getContentAsString();
            String liveId = response.replaceAll(".*\"live_eval_id\":\"([^\"]+)\".*", "$1");
            assertThat(jdbc.queryForObject("SELECT payload->>'from_seq' || '~' || (payload->>'to_seq') FROM outbox_events "
                    + "WHERE type = 'LIVE_EVALUATE' AND aggregate_id = ?", String.class, liveId)).isEqualTo("3~12");

            mvc.perform(auth(post("/api/live-evaluations"), member).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
                    .andExpect(jsonPath("$.error.retry_after_seconds").isNumber());

            mvc.perform(auth(get("/api/live-evaluations/" + liveId), member))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.live_eval_id").value(liveId))
                    .andExpect(jsonPath("$.data.phase").value("waiting"))
                    .andExpect(jsonPath("$.data.display").doesNotExist());
            // Agent가 저장한 결과는 패널이 그대로 쓰는 모양(display)으로도 내려간다
            jdbc.update("UPDATE live_evaluations SET status = '완료', items = ?::jsonb, coaching = ?::jsonb WHERE live_eval_id = ?",
                    "[{\"item_id\":\"f1\",\"kind\":\"improvement\",\"diagnosis\":\"자료와 대조하지 않았어요\",\"evidence\":[]}]",
                    "{\"schema_version\":\"1\",\"provenance\":{},\"suggestions\":[{\"item_id\":\"f1\","
                            + "\"target_message_id\":\"m\",\"next_action\":\"가격표와 대조해 보세요\",\"suggested_prompt\":\"가격표 v2와 대조해줘\"}]}",
                    liveId);
            entityManager.clear();
            mvc.perform(auth(get("/api/live-evaluations/" + liveId), member))
                    .andExpect(jsonPath("$.data.phase").value("done"))
                    .andExpect(jsonPath("$.data.display.items[0].label").value("보완할 점"))
                    .andExpect(jsonPath("$.data.display.items[0].state").value("warn"))
                    .andExpect(jsonPath("$.data.display.items[0].note").value("자료와 대조하지 않았어요"))
                    .andExpect(jsonPath("$.data.display.coaching").value("가격표와 대조해 보세요"))
                    .andExpect(jsonPath("$.data.display.suggested_prompt").value("가격표 v2와 대조해줘"));
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
