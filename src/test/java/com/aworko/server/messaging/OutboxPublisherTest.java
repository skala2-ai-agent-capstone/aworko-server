package com.aworko.server.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.aworko.server.support.TestJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock OutboxEventRepository repository;
    OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(repository, TestJson.mapper());
    }

    @Test
    void evaluateStoresSnakeCaseIdsOnlyPayloadWithRoutingKey() {
        var msg = publisher.evaluate("EVAL-E2E-QT-08-v2", "C-LGCNS-SALES", "W-E2E-QT-08", "v2", "v2");

        OutboxEvent saved = captureSaved();
        assertThat(saved.getType()).isEqualTo("EVALUATE");
        assertThat(saved.getRoutingKey()).isEqualTo(Topology.RK_EVALUATE);
        assertThat(saved.getAggregateId()).isEqualTo("EVAL-E2E-QT-08-v2");
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.PENDING);

        JsonNode p = saved.getPayload();
        assertThat(p.get("message_id").asText()).isEqualTo(msg.messageId()).isNotBlank();
        assertThat(p.get("evaluation_id").asText()).isEqualTo("EVAL-E2E-QT-08-v2");
        assertThat(p.get("task_id").asText()).isEqualTo("W-E2E-QT-08");
        assertThat(p.get("deliverable_version").asText()).isEqualTo("v2");
        assertThat(p.get("rubric_version").asText()).isEqualTo("v2");
        assertThat(p.get("attempt").asInt()).isZero();
        // 계약 밖 필드(원문 등)가 섞이지 않는다
        assertThat(p.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
                "message_id", "type", "evaluation_id", "company_id", "task_id", "deliverable_version",
                "rubric_version", "attempt");
    }

    @Test
    void resumeUsesResumeRoutingKey() {
        publisher.resume("EVAL-1", "appeal");

        OutboxEvent saved = captureSaved();
        assertThat(saved.getType()).isEqualTo("RESUME");
        assertThat(saved.getRoutingKey()).isEqualTo(Topology.RK_RESUME);
        assertThat(saved.getPayload().get("action").asText()).isEqualTo("appeal");
    }

    @Test
    void liveEvaluateCarriesFixedMessageRange() {
        publisher.liveEvaluate("LIVE-1", "C-LGCNS-SALES", "W-1", "E-1021", 3, 7, null);

        OutboxEvent saved = captureSaved();
        assertThat(saved.getType()).isEqualTo("LIVE_EVALUATE");
        assertThat(saved.getRoutingKey()).isEqualTo(Topology.RK_LIVE_EVALUATE);
        JsonNode p = saved.getPayload();
        assertThat(p.get("live_eval_id").asText()).isEqualTo("LIVE-1");
        assertThat(p.get("from_seq").asInt()).isEqualTo(3);
        assertThat(p.get("to_seq").asInt()).isEqualTo(7);
        assertThat(p.get("requested_by").asText()).isEqualTo("E-1021");
    }

    private OutboxEvent captureSaved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
