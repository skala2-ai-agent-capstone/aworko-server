package com.aworko.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aworko.server.messaging.MessagingProperties;
import com.aworko.server.messaging.Topology;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

class RabbitConfigTest {

    final RabbitConfig config = new RabbitConfig();
    final MessagingProperties props = new MessagingProperties(
            new MessagingProperties.Outbox(true, Duration.ofSeconds(1), 50, Duration.ofSeconds(5)),
            Duration.ofSeconds(30), Duration.ofSeconds(5));

    @Test
    void evaluationRetryQueueDeadLettersBackToRequestsAfterDelay() {
        Queue retry = queue(config.evaluationTopology(props), Topology.EVALUATION_RETRY);
        assertThat(retry.isDurable()).isTrue();
        assertThat(retry.getArguments())
                .containsEntry("x-message-ttl", 30_000)
                .containsEntry("x-dead-letter-exchange", Topology.EVALUATION_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", Topology.RK_EVALUATE);
    }

    @Test
    void evaluationRoutingKeysBindToExpectedQueues() {
        List<Binding> bindings = config.evaluationTopology(props).getDeclarablesByType(Binding.class);
        assertThat(bindings).extracting(b -> b.getRoutingKey() + "->" + b.getDestination()).containsExactlyInAnyOrder(
                "evaluate->" + Topology.EVALUATION_REQUESTS,
                "resume->" + Topology.EVALUATION_REQUESTS,
                Topology.RK_EVALUATION_RETRY + "->" + Topology.EVALUATION_RETRY,
                Topology.RK_EVALUATION_DLQ + "->" + Topology.EVALUATION_DLQ);
    }

    @Test
    void liveEvaluationTopologyIsSeparateWithShortRetry() {
        Declarables live = config.liveEvaluationTopology(props);
        Queue retry = queue(live, Topology.LIVE_RETRY);
        assertThat(retry.getArguments())
                .containsEntry("x-message-ttl", 5_000)
                .containsEntry("x-dead-letter-exchange", Topology.LIVE_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", Topology.RK_LIVE_EVALUATE);
        assertThat(live.getDeclarablesByType(Binding.class)).allMatch(b -> b.getExchange().equals(Topology.LIVE_EXCHANGE));
    }

    private static Queue queue(Declarables declarables, String name) {
        return declarables.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals(name)).findFirst().orElseThrow();
    }
}
