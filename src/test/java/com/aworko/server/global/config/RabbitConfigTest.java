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

    /** Agent 요청 큐에는 EVALUATE만 들어간다(resume 바인딩 없음). 완료 알림 큐는 백엔드가 먼저 만든다 */
    @Test
    void evaluationRoutingKeysBindToExpectedQueues() {
        Declarables topology = config.evaluationTopology(props);
        assertThat(topology.getDeclarablesByType(Binding.class))
                .extracting(b -> b.getRoutingKey() + "->" + b.getDestination()).containsExactlyInAnyOrder(
                        "evaluate->" + Topology.EVALUATION_REQUESTS,
                        Topology.RK_EVALUATION_RETRY + "->" + Topology.EVALUATION_RETRY,
                        Topology.RK_EVALUATION_DLQ + "->" + Topology.EVALUATION_DLQ,
                        Topology.RK_EVALUATION_RESULTS + "->" + Topology.EVALUATION_RESULTS);
        assertThat(queue(topology, Topology.EVALUATION_RESULTS).isDurable()).isTrue();
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

    /** 팀 평가는 결과 큐 없이 requests·retry·dlq만. 재시도는 TTL 뒤 team_evaluate로 요청 큐에 돌아간다 */
    @Test
    void teamEvaluationTopologyRetriesBackToRequests() {
        Declarables team = config.teamEvaluationTopology(props);
        assertThat(team.getDeclarablesByType(Queue.class)).extracting(Queue::getName)
                .containsExactlyInAnyOrder(Topology.TEAM_REQUESTS, Topology.TEAM_RETRY, Topology.TEAM_DLQ);
        assertThat(queue(team, Topology.TEAM_RETRY).getArguments())
                .containsEntry("x-message-ttl", 30_000)
                .containsEntry("x-dead-letter-exchange", Topology.TEAM_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", Topology.RK_TEAM_EVALUATE);
    }

    private static Queue queue(Declarables declarables, String name) {
        return declarables.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals(name)).findFirst().orElseThrow();
    }
}
