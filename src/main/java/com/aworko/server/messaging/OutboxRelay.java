package com.aworko.server.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PENDING 아웃박스 행을 RabbitMQ로 발행하고, 브로커 수신 확인(confirm)을 받은 뒤 SENT로 표시한다.
 * 발행에 실패하면 attempts·last_error만 남기고 다음 주기에 다시 시도한다(순서 유지를 위해 그 배치는 거기서 멈춘다).
 * confirm 후 커밋 전에 죽으면 같은 메시지가 한 번 더 나갈 수 있다 → Agent가 message_id·평가 키로 중복을 거른다(at-least-once).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    static final String HEADER_TYPE = "type";

    private final OutboxEventRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties props;

    /** @return 이번에 발행한 건수 */
    @Transactional
    public int relayOnce() {
        List<OutboxEvent> batch = outboxRepository.lockPending(props.outbox().batchSize());
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                publish(event);
                event.markSent();
                sent++;
            } catch (RuntimeException e) {
                log.warn("Outbox publish failed: id={}, type={}, attempt={}", event.getId(), event.getType(),
                        event.getAttempts() + 1, e);
                event.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
                break;
            }
        }
        return sent;
    }

    private void publish(OutboxEvent event) {
        MessageType type = MessageType.valueOf(event.getType());
        Message message = toMessage(event);
        long timeoutMs = props.outbox().confirmTimeout().toMillis();
        rabbitTemplate.invoke(ops -> {
            ops.send(type.getExchange(), event.getRoutingKey(), message);
            ops.waitForConfirmsOrDie(timeoutMs);
            return null;
        });
    }

    static Message toMessage(OutboxEvent event) {
        byte[] body = event.getPayload().toString().getBytes(StandardCharsets.UTF_8);
        return MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.getPayload().path("message_id").asText(null))
                .setHeader(HEADER_TYPE, event.getType())
                .build();
    }
}
