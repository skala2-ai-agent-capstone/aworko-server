package com.aworko.server.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * DLQ 소비. 메시지를 DB(evaluation_failures 또는 live_evaluations.error)에 남긴 뒤 ack한다.
 * Agent는 DLQ로 보낼 때 실패 사유를 x-failure-reason 헤더에 넣을 수 있다(선택).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterListener {

    static final String HEADER_REASON = "x-failure-reason";

    private final DeadLetterService deadLetterService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = Topology.EVALUATION_DLQ)
    public void onEvaluationDeadLetter(Message message) {
        deadLetterService.recordEvaluationFailure(parse(message), reason(message));
    }

    @RabbitListener(queues = Topology.LIVE_DLQ)
    public void onLiveEvaluationDeadLetter(Message message) {
        deadLetterService.recordLiveEvaluationFailure(parse(message), reason(message));
    }

    private JsonNode parse(Message message) {
        try {
            return objectMapper.readTree(message.getBody());
        } catch (IOException e) {
            // 형식이 깨진 메시지는 다시 넣어도 처리할 수 없으므로 버린다(원문은 로그에 남기지 않는다)
            log.warn("Unparsable DLQ message dropped: message_id={}", message.getMessageProperties().getMessageId());
            throw new AmqpRejectAndDontRequeueException("Unparsable DLQ message", e);
        }
    }

    private static String reason(Message message) {
        Object v = message.getMessageProperties().getHeader(HEADER_REASON);
        return v == null ? null : v.toString();
    }
}
