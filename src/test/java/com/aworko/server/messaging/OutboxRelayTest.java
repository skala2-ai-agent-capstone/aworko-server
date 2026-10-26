package com.aworko.server.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aworko.server.support.TestJson;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock OutboxEventRepository repository;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock RabbitOperations ops;
    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        var props = new MessagingProperties(
                new MessagingProperties.Outbox(true, Duration.ofSeconds(1), 10, Duration.ofSeconds(5)),
                Duration.ofSeconds(30), Duration.ofSeconds(5));
        relay = new OutboxRelay(repository, rabbitTemplate, props);
        // invoke(callback)가 콜백을 mock 채널(ops)로 실행하게 한다
        when(rabbitTemplate.invoke(any())).thenAnswer(inv ->
                ((RabbitOperations.OperationsCallback<?>) inv.getArgument(0)).doInRabbit(ops));
    }

    @Test
    void publishesEachPendingEventToItsExchangeAndMarksSent() {
        OutboxEvent evaluate = event("EVALUATE", Topology.RK_EVALUATE, "m-1");
        OutboxEvent live = event("LIVE_EVALUATE", Topology.RK_LIVE_EVALUATE, "m-2");
        when(repository.lockPending(10)).thenReturn(List.of(evaluate, live));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(2);
        verify(ops).send(eq(Topology.EVALUATION_EXCHANGE), eq(Topology.RK_EVALUATE), any(Message.class));
        verify(ops).send(eq(Topology.LIVE_EXCHANGE), eq(Topology.RK_LIVE_EVALUATE), any(Message.class));
        verify(ops, times(2)).waitForConfirmsOrDie(5000L);
        assertThat(evaluate.getStatus()).isEqualTo(OutboxEvent.SENT);
        assertThat(evaluate.getSentAt()).isNotNull();
        assertThat(live.getStatus()).isEqualTo(OutboxEvent.SENT);
    }

    @Test
    void stopsBatchAndKeepsPendingWhenBrokerDoesNotConfirm() {
        OutboxEvent first = event("EVALUATE", Topology.RK_EVALUATE, "m-1");
        OutboxEvent second = event("LIVE_EVALUATE", Topology.RK_LIVE_EVALUATE, "m-2");
        when(repository.lockPending(10)).thenReturn(List.of(first, second));
        doThrow(new AmqpException("nack")).when(ops).waitForConfirmsOrDie(anyLong());

        int sent = relay.relayOnce();

        assertThat(sent).isZero();
        assertThat(first.getStatus()).isEqualTo(OutboxEvent.PENDING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).contains("nack");
        // 순서 보장을 위해 뒤의 메시지는 이번 주기에 보내지 않는다
        verify(ops, times(1)).send(any(String.class), any(String.class), any(Message.class));
        assertThat(second.getAttempts()).isZero();
    }

    @Test
    void messageIsPersistentJsonWithMessageIdAndTypeHeader() {
        OutboxEvent e = event("EVALUATE", Topology.RK_EVALUATE, "m-9");
        when(repository.lockPending(10)).thenReturn(List.of(e));

        relay.relayOnce();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(ops).send(eq(Topology.EVALUATION_EXCHANGE), eq(Topology.RK_EVALUATE), captor.capture());
        var props = captor.getValue().getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo("m-9");
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat(props.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat((String) props.getHeader(OutboxRelay.HEADER_TYPE)).isEqualTo("EVALUATE");
        assertThat(new String(captor.getValue().getBody())).contains("\"evaluation_id\":\"EVAL-1\"");
    }

    private static OutboxEvent event(String type, String routingKey, String messageId) {
        var payload = TestJson.mapper().createObjectNode()
                .put("message_id", messageId).put("type", type).put("evaluation_id", "EVAL-1");
        return OutboxEvent.pending("EVAL-1", type, routingKey, payload);
    }
}
