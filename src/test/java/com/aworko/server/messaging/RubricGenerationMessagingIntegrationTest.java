package com.aworko.server.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aworko.server.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * 실제 RabbitMQ(Testcontainers)로 평가 기준 생성 큐를 확인한다: RUBRIC_GENERATE 발행 → 요청 큐 도착,
 * Agent의 완료(results)·실패(DLQ, error_code 헤더) → rubric_generations 기록.
 */
@SpringBootTest(properties = "app.messaging.outbox.enabled=false")
@Import(TestcontainersConfiguration.class)
@Sql("/db/seed_minimal.sql")
class RubricGenerationMessagingIntegrationTest {

    static final String COMPANY = "C-LGCNS-SALES";

    @Autowired OutboxPublisher publisher;
    @Autowired OutboxRelay relay;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        amqpAdmin.purgeQueue(Topology.RUBRIC_REQUESTS, false);
        amqpAdmin.purgeQueue(Topology.RUBRIC_RETRY, false);
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM rubric_generations");
    }

    @Test
    void topologyIsDeclaredOnStartup() {
        for (String q : new String[]{Topology.RUBRIC_REQUESTS, Topology.RUBRIC_RETRY, Topology.RUBRIC_DLQ,
                Topology.RUBRIC_RESULTS}) {
            assertThat(amqpAdmin.getQueueProperties(q)).as(q).isNotNull();
        }
    }

    @Test
    void rubricGenerateIsRelayedToTheRequestQueue() throws Exception {
        var msg = tx.execute(s -> publisher.rubricGenerate("RG-RELAY-1", COMPANY, "POL-SALES", "v1.2",
                List.of("quote", "proposal"), "L-3001"));

        assertThat(relay.relayOnce()).isEqualTo(1);

        Message received = rabbitTemplate.receive(Topology.RUBRIC_REQUESTS, 5000);
        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(msg.messageId());
        JsonNode body = objectMapper.readTree(received.getBody());
        assertThat(body.path("type").asText()).isEqualTo("RUBRIC_GENERATE");
        assertThat(body.path("generation_id").asText()).isEqualTo("RG-RELAY-1");
        assertThat(body.path("policy_version").asText()).isEqualTo("v1.2");
        assertThat(body.path("work_types").get(1).asText()).isEqualTo("proposal");
    }

    @Test
    void resultMessageCompletesTheGeneration() {
        String id = pending();

        rabbitTemplate.send(Topology.RUBRIC_EXCHANGE, Topology.RK_RUBRIC_RESULTS, json("{\"type\":\"RUBRIC_GENERATED\","
                + "\"generation_id\":\"" + id + "\",\"company_id\":\"" + COMPANY + "\",\"drafts\":["
                + "{\"work_type\":\"quote\",\"doc_id\":\"RUB-quote\",\"version\":\"v3\"}]}"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> row = row(id);
            assertThat(row.get("status")).isEqualTo("완료");
            assertThat((String) row.get("drafts")).contains("RUB-quote");
        });
    }

    @Test
    void deadLetterRecordsTheWorkerErrorCode() {
        String id = pending();

        Message dead = MessageBuilder.fromMessage(json("{\"type\":\"RUBRIC_GENERATE\",\"generation_id\":\"" + id
                        + "\",\"attempt\":3}"))
                .setHeader(DeadLetterListener.HEADER_ERROR_CODE, "RUBRIC_INVALID").build();
        rabbitTemplate.send(Topology.RUBRIC_EXCHANGE, Topology.RK_RUBRIC_DLQ, dead);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> row = row(id);
            assertThat(row.get("status")).isEqualTo("실패");
            assertThat(row.get("error")).isEqualTo("RUBRIC_INVALID");
        });
    }

    private String pending() {
        String id = "RG-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO rubric_generations (generation_id, company_id, policy_doc_id, policy_version, work_types, "
                + "requested_by) VALUES (?, ?, 'POL-SALES', 'v1.2', ARRAY['quote'], 'L-3001')", id, COMPANY);
        return id;
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("SELECT status, error, drafts::text AS drafts FROM rubric_generations WHERE generation_id = ?",
                id);
    }

    private static Message json(String body) {
        return MessageBuilder.withBody(body.getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(UUID.randomUUID().toString())
                .build();
    }
}
