package com.aworko.server.messaging;

import com.aworko.server.aggregation.TeamEvaluationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** team_evaluation.dlq 소비. 실패 사유는 Worker가 error_code 헤더에 넣는다(원문 없음). 완료는 큐가 아니라 MCP에 남는다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamEvaluationListener {

    private final TeamEvaluationService teamEvaluationService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = Topology.TEAM_DLQ)
    public void onDeadLetter(Message message) {
        Object reason = message.getMessageProperties().getHeader(DeadLetterListener.HEADER_REASON);
        if (reason == null) {
            reason = message.getMessageProperties().getHeader(DeadLetterListener.HEADER_ERROR_CODE);
        }
        teamEvaluationService.fail(parse(message), reason == null ? null : reason.toString());
    }

    private JsonNode parse(Message message) {
        try {
            return objectMapper.readTree(message.getBody());
        } catch (IOException e) {
            log.warn("Unparsable team evaluation DLQ message dropped: message_id={}",
                    message.getMessageProperties().getMessageId());
            throw new AmqpRejectAndDontRequeueException("Unparsable team evaluation DLQ message", e);
        }
    }
}
