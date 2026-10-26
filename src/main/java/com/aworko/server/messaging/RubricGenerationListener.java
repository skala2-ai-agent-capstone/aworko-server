package com.aworko.server.messaging;

import com.aworko.server.settings.RubricGenerationService;
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
 * 평가 기준 생성 결과 소비. results(RUBRIC_GENERATED: 초안 저장 완료)와 DLQ(실패)를 rubric_generations에 기록한 뒤 ack한다.
 * 실패 사유는 Agent가 넣은 error_code(또는 x-failure-reason) 헤더다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RubricGenerationListener {

    private final RubricGenerationService rubricGenerationService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = Topology.RUBRIC_RESULTS)
    public void onResult(Message message) {
        rubricGenerationService.complete(parse(message));
    }

    @RabbitListener(queues = Topology.RUBRIC_DLQ)
    public void onDeadLetter(Message message) {
        Object reason = message.getMessageProperties().getHeader(DeadLetterListener.HEADER_REASON);
        if (reason == null) {
            reason = message.getMessageProperties().getHeader(DeadLetterListener.HEADER_ERROR_CODE);
        }
        rubricGenerationService.fail(parse(message), reason == null ? null : reason.toString());
    }

    private JsonNode parse(Message message) {
        try {
            return objectMapper.readTree(message.getBody());
        } catch (IOException e) {
            // 형식이 깨진 메시지는 다시 넣어도 처리할 수 없으므로 버린다(원문은 로그에 남기지 않는다)
            log.warn("Unparsable rubric generation message dropped: message_id={}",
                    message.getMessageProperties().getMessageId());
            throw new AmqpRejectAndDontRequeueException("Unparsable rubric generation message", e);
        }
    }
}
