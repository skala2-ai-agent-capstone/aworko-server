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
                Topology.LIVE_REQUESTS, Topology.LIVE_RETRY, Topology.LIVE_DLQ}) {
            assertThat(amqpAdmin.getQueueProperties(q)).as(q).isNotNull();
        }
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
            publisher.resume("EVAL-ROLLBACK", "confirm");
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

    @Test
    void liveDeadLetterMarksRequestFailed() {
        LiveEvaluation live = tx.execute(s -> liveEvaluations.save(
                LiveEvaluation.request("W-TEST-QT-01", "E-1021", 1, 5, null, null, OffsetDateTime.now())));

        rabbitTemplate.send(Topology.LIVE_EXCHANGE, Topology.RK_LIVE_DLQ,
                json("{\"live_eval_id\":\"" + live.getLiveEvalId() + "\"}"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM live_evaluations WHERE live_eval_id = ?",
                        String.class, live.getLiveEvalId())).isEqualTo("실패"));
    }

    private static Message json(String body) {
        return MessageBuilder.withBody(body.getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
    }
}
