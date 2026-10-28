package com.aworko.server.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aworko.server.evaluation.Evaluation;
import com.aworko.server.evaluation.EvaluationRepository;
import com.aworko.server.support.TestcontainersConfiguration;
import com.aworko.server.work.LiveEvaluation;
import com.aworko.server.work.LiveEvaluationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 RabbitMQ(Testcontainers)로 아웃박스 발행 → 큐 도착, 재시도 큐 복귀, DLQ → 실패 기록까지 확인한다.
 * 스케줄러는 끄고 relayOnce()를 직접 호출한다. 재시도 지연은 짧게 줄였다.
 */
@SpringBootTest(properties = {
        "app.messaging.outbox.enabled=false",
        "app.messaging.evaluation-retry-delay=500ms",
        "app.messaging.live-evaluation-retry-delay=500ms"
})
@Import(TestcontainersConfiguration.class)
@Sql("/db/seed_minimal.sql")
class MessagingIntegrationTest {

    @Autowired OutboxPublisher publisher;
    @Autowired OutboxRelay relay;
    @Autowired OutboxEventRepository outbox;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;
    @Autowired EvaluationRepository evaluations;
    @Autowired LiveEvaluationRepository liveEvaluations;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        for (String q : new String[]{Topology.EVALUATION_REQUESTS, Topology.EVALUATION_RETRY,
                Topology.LIVE_REQUESTS, Topology.LIVE_RETRY}) {
            amqpAdmin.purgeQueue(q, false);
        }
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void topologyIsDeclaredOnStartup() {
        for (String q : new String[]{Topology.EVALUATION_REQUESTS, Topology.EVALUATION_RETRY, Topology.EVALUATION_DLQ,
                Topology.EVALUATION_RESULTS, Topology.LIVE_REQUESTS, Topology.LIVE_RETRY, Topology.LIVE_DLQ,
                Topology.TEAM_REQUESTS, Topology.TEAM_RETRY, Topology.TEAM_DLQ}) {
            assertThat(amqpAdmin.getQueueProperties(q)).as(q).isNotNull();
        }
    }

    @Test
    void teamEvaluateGoesToTeamQueueAndDeadLetterRecordsFailure() throws Exception {
        String requestId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO team_evaluations (request_id, snapshot_id, company_id, team_id, period_start, period_end, "
                        + "release_approved, min_members, snapshot, snapshot_sha256, source_evaluation_ids) "
                        + "VALUES (?::uuid, ?::uuid, 'C-LGCNS-SALES', 'T-SALES-01', '2026-10-19', '2026-10-26', false, 5, "
                        + "'{}'::jsonb, ?, '{}')", requestId, UUID.randomUUID().toString(), "0".repeat(64));
        amqpAdmin.purgeQueue(Topology.TEAM_REQUESTS, false);
        tx.execute(s -> publisher.teamEvaluate(requestId, "C-LGCNS-SALES"));

        relay.relayOnce();

        Message received = rabbitTemplate.receive(Topology.TEAM_REQUESTS, 5000);
        assertThat(received).isNotNull();
        JsonNode body = objectMapper.readTree(received.getBody());
        assertThat(body.get("type").asText()).isEqualTo("TEAM_EVALUATE");
        assertThat(body.get("request_id").asText()).isEqualTo(requestId);
        assertThat(body.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("message_id", "type", "request_id", "company_id", "attempt");

        // Worker는 실패 사유를 error_code 헤더에 넣어 기본 exchange로 DLQ에 보낸다
        Message dead = MessageBuilder.fromMessage(json(new String(received.getBody())))
                .setHeader(DeadLetterListener.HEADER_ERROR_CODE, "LLM_UNAVAILABLE").build();
        rabbitTemplate.send("", Topology.TEAM_DLQ, dead);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForList("SELECT reason FROM team_evaluation_failures WHERE request_id = ?::uuid",
                        String.class, requestId)).containsExactly("LLM_UNAVAILABLE"));
    }

    /** 실행 토큰은 EVALUATE를 보낸 평가 건에만 준다. 시드로만 들어온 대기 건은 Worker가 받지 않는다 */
    @Test
    void onlyQueuedInProgressEvaluationsNeedRunTokens() {
        String queued = "EVAL-Q-" + UUID.randomUUID().toString().substring(0, 8);
        String seeded = "EVAL-S-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(s -> {
            evaluations.save(Evaluation.create(queued, "W-TEST-QT-01", "C-LGCNS-SALES", "ART-TEST-QT-01", "v2", "v2", queued));
            evaluations.save(Evaluation.create(seeded, "W-TEST-QT-01", "C-LGCNS-SALES", "ART-TEST-QT-01", "v2", "v2", seeded));
            publisher.evaluate(queued, "C-LGCNS-SALES", "W-TEST-QT-01", "v2", "v2");
        });

        assertThat(evaluations.findQueuedByStatusIn(com.aworko.server.evaluation.EvaluationStatus.IN_PROGRESS))
                .extracting(Evaluation::getEvaluationId).contains(queued).doesNotContain(seeded);
    }

    @Test
    void legacyResumeRoutingKeyNoLongerReachesAgentRequests() {
        // 예전 RESUME 바인딩이 없으므로 resume으로 보내도 Agent 요청 큐에 들어가지 않는다
        rabbitTemplate.send(Topology.EVALUATION_EXCHANGE, "resume", json("{\"type\":\"RESUME\"}"));

        assertThat(rabbitTemplate.receive(Topology.EVALUATION_REQUESTS, 500)).isNull();
    }

    @Test
    void outboxEventIsPublishedOnlyAfterCommitAndMarkedSent() throws Exception {
        var msg = tx.execute(s -> publisher.evaluate("EVAL-OUTBOX-1", "C-LGCNS-SALES", "W-TEST-QT-01", "v2", "v2"));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        Message received = rabbitTemplate.receive(Topology.EVALUATION_REQUESTS, 5000);
        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(msg.messageId());
        JsonNode body = objectMapper.readTree(received.getBody());
        assertThat(body.get("evaluation_id").asText()).isEqualTo("EVAL-OUTBOX-1");
        assertThat(body.get("task_id").asText()).isEqualTo("W-TEST-QT-01");
        assertThat(outbox.findAll()).allMatch(e -> OutboxEvent.SENT.equals(e.getStatus()));

        // 이미 보낸 건은 다시 보내지 않는다
        assertThat(relay.relayOnce()).isZero();
    }

    @Test
    void rolledBackTransactionLeavesNothingToPublish() {
        tx.execute(s -> {
            publisher.evaluate("EVAL-ROLLBACK", "C-LGCNS-SALES", "W-TEST-QT-01", "v2", "v2");
            s.setRollbackOnly();
            return null;
        });

        assertThat(relay.relayOnce()).isZero();
        assertThat(rabbitTemplate.receive(Topology.EVALUATION_REQUESTS, 500)).isNull();
    }

    @Test
    void liveEvaluateGoesToSeparateQueue() throws Exception {
        tx.execute(s -> publisher.liveEvaluate("LIVE-Q-1", "C-LGCNS-SALES", "W-TEST-QT-01", "E-1021", 1, 5, "v2"));

        relay.relayOnce();

        assertThat(rabbitTemplate.receive(Topology.EVALUATION_REQUESTS, 500)).isNull();
        Message received = rabbitTemplate.receive(Topology.LIVE_REQUESTS, 5000);
        assertThat(received).isNotNull();
        assertThat(objectMapper.readTree(received.getBody()).get("live_eval_id").asText()).isEqualTo("LIVE-Q-1");
    }

    @Test
    void retryQueueReturnsMessageToRequestsAfterDelay() {
        rabbitTemplate.send(Topology.EVALUATION_EXCHANGE, Topology.RK_EVALUATION_RETRY, json("{\"attempt\":1}"));

        Message back = rabbitTemplate.receive(Topology.EVALUATION_REQUESTS, 10_000);
        assertThat(back).isNotNull();
        assertThat(new String(back.getBody())).contains("\"attempt\":1");
    }

    @Test
    void evaluationDeadLetterMarksEvaluationFailedAndKeepsMessage() {
        String id = "EVAL-DLQ-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(s -> evaluations.save(
                Evaluation.create(id, "W-TEST-QT-01", "C-LGCNS-SALES", "ART-TEST-QT-01", "v2", "v2", id)));

        Message dead = MessageBuilder.fromMessage(json("{\"evaluation_id\":\"" + id + "\",\"attempt\":3}"))
                .setHeader(DeadLetterListener.HEADER_REASON, "LLM timeout").build();
        rabbitTemplate.send(Topology.EVALUATION_EXCHANGE, Topology.RK_EVALUATION_DLQ, dead);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT status FROM evaluations WHERE evaluation_id = ?", String.class, id))
                    .isEqualTo("처리 실패");
            assertThat(jdbc.queryForObject(
                    "SELECT reason FROM evaluation_failures WHERE evaluation_id = ?", String.class, id))
                    .isEqualTo("LLM timeout");
        });
    }

    /** 재처리로 새 EVALUATE를 보낸 뒤 늦게 온 이전 요청의 DLQ는 무시하고, 최신 요청의 실패만 기록한다(아웃박스 조회 포함) */
    @Test
    void deadLetterOfAnEarlierRequestIsIgnoredAfterReprocessing() {
        String id = "EVAL-DLQ-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(s -> evaluations.save(
                Evaluation.create(id, "W-TEST-QT-01", "C-LGCNS-SALES", "ART-TEST-QT-01", "v2", "v2", id)));
        var first = tx.execute(s -> publisher.evaluate(id, "C-LGCNS-SALES", "W-TEST-QT-01", "v2", "v2"));
        var retried = tx.execute(s -> publisher.evaluate(id, "C-LGCNS-SALES", "W-TEST-QT-01", "v2", "v2"));

        for (var sent : new EvaluationMessages.Evaluate[]{first, retried}) {
            Message dead = MessageBuilder.fromMessage(json("{\"message_id\":\"" + sent.messageId()
                    + "\",\"evaluation_id\":\"" + id + "\",\"attempt\":3}")).build();
            rabbitTemplate.send(Topology.EVALUATION_EXCHANGE, Topology.RK_EVALUATION_DLQ, dead);
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM evaluations WHERE evaluation_id = ?", String.class, id)).isEqualTo("처리 실패"));
        assertThat(jdbc.queryForList("SELECT message->>'message_id' FROM evaluation_failures WHERE evaluation_id = ?",
                String.class, id)).containsExactly(retried.messageId());
    }

    @Test
    void liveDeadLetterMarksRequestFailedWithWorkerErrorCode() {
        LiveEvaluation live = tx.execute(s -> liveEvaluations.save(
                LiveEvaluation.request("W-TEST-QT-01", "E-1021", 1, 5, null, null, OffsetDateTime.now())));

        // 피드백 Worker는 사유를 error_code 헤더에 넣는다
        Message dead = MessageBuilder.fromMessage(json("{\"live_eval_id\":\"" + live.getLiveEvalId() + "\"}"))
                .setHeader(DeadLetterListener.HEADER_ERROR_CODE, "MCP_REJECTED").build();
        rabbitTemplate.send(Topology.LIVE_EXCHANGE, Topology.RK_LIVE_DLQ, dead);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForMap("SELECT status, error FROM live_evaluations WHERE live_eval_id = ?",
                        live.getLiveEvalId())).containsEntry("status", "실패").containsEntry("error", "MCP_REJECTED"));
    }

    /**
     * Agent Worker(backend-v1)처럼 기본 exchange로 evaluation.results에 직접 보낸다.
     * 처리 중이면 확인 대기, 같은 message_id 재전달은 무시, 더 뒤 상태(확정)는 되돌리지 않는다.
     */
    @Test
    void finalEvaluationResultMovesToAwaitingConfirmationOnceAndNeverRegresses() {
        String id = savedEvaluation("평가 중", 1, 1);

        rabbitTemplate.send("", Topology.EVALUATION_RESULTS, savedResult("final-saved:" + id, id, 1, 1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(statusOf(id)).isEqualTo("확인 대기");
            assertThat(outcomes(id)).containsExactly("APPLIED");
        });

        // 사용자가 확정한 뒤 같은 알림이 다시 오거나(재전달) 다른 실행의 알림이 와도 확인 대기로 되돌리지 않는다
        jdbc.update("UPDATE evaluations SET status = '확정' WHERE evaluation_id = ?", id);
        rabbitTemplate.send("", Topology.EVALUATION_RESULTS, savedResult("final-saved:" + id, id, 1, 1));
        rabbitTemplate.send("", Topology.EVALUATION_RESULTS, savedResult("final-saved:" + id + ":2", id, 1, 1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(outcomes(id)).containsExactly("APPLIED", "STALE"));
        assertThat(statusOf(id)).isEqualTo("확정");
    }

    @Test
    void finalEvaluationResultWithoutStoredRevisionsLeavesStatus() {
        String id = savedEvaluation("코칭 생성 중", 1, 1);

        rabbitTemplate.send("", Topology.EVALUATION_RESULTS, savedResult("final-saved:" + id, id, 2, 1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(outcomes(id)).containsExactly("RECEIPT_MISMATCH"));
        assertThat(statusOf(id)).isEqualTo("코칭 생성 중");
    }

    @Test
    void malformedFinalEvaluationResultIsDroppedAndLaterResultsStillApply() {
        String id = savedEvaluation("대기", 1, 1);

        rabbitTemplate.send("", Topology.EVALUATION_RESULTS, json("{\"type\":\"FINAL_EVALUATION_SAVED\",\"evaluation_id\":\""
                + id + "\"}"));
        rabbitTemplate.send("", Topology.EVALUATION_RESULTS, json("not json"));
        rabbitTemplate.send("", Topology.EVALUATION_RESULTS, savedResult("final-saved:" + id, id, 1, 1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusOf(id)).isEqualTo("확인 대기"));
        assertThat(outcomes(id)).containsExactly("APPLIED");
    }

    /** 평가 건과 초안·코칭 판을 만든다(Agent가 MCP로 저장한 상태) */
    private String savedEvaluation(String status, int draftRevision, int coachingRevision) {
        String id = "EVAL-RES-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(s -> evaluations.save(
                Evaluation.create(id, "W-TEST-QT-01", "C-LGCNS-SALES", "ART-TEST-QT-01", "v2", "v2", id)));
        jdbc.update("UPDATE evaluations SET status = ? WHERE evaluation_id = ?", status, id);
        jdbc.update("INSERT INTO evaluation_drafts (evaluation_id, revision, items) VALUES (?, ?, '[]'::jsonb)",
                id, draftRevision);
        jdbc.update("INSERT INTO evaluation_coaching (evaluation_id, revision, items) VALUES (?, ?, '[]'::jsonb)",
                id, coachingRevision);
        return id;
    }

    /** Agent FinalEvaluationSaved와 같은 본문·헤더 */
    private static Message savedResult(String messageId, String evaluationId, int draftRevision, int coachingRevision) {
        return MessageBuilder.fromMessage(json("{\"schema_version\":\"1\",\"type\":\"FINAL_EVALUATION_SAVED\","
                        + "\"message_id\":\"" + messageId + "\",\"evaluation_id\":\"" + evaluationId + "\","
                        + "\"operation_id\":\"backend-v1:test\",\"status\":\"draft_and_coaching_saved\","
                        + "\"review_required\":true,\"draft_revision\":" + draftRevision + ","
                        + "\"coaching_revision\":" + coachingRevision + "}"))
                .setMessageId(messageId).setHeader("error_code", "SAVED").build();
    }

    private String statusOf(String evaluationId) {
        return jdbc.queryForObject("SELECT status FROM evaluations WHERE evaluation_id = ?", String.class, evaluationId);
    }

    private java.util.List<String> outcomes(String evaluationId) {
        return jdbc.queryForList("SELECT outcome FROM evaluation_result_receipts WHERE evaluation_id = ? "
                + "ORDER BY received_at, message_id", String.class, evaluationId);
    }

    private static Message json(String body) {
        return MessageBuilder.withBody(body.getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
    }
}
