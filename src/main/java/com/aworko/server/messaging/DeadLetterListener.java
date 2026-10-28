package com.aworko.server.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * DLQ 소비. 메시지를 DB(evaluation_failures 또는 live_evaluations.error)에 남긴 뒤 ack한다.
 * Agent는 DLQ로 보낼 때 실패 사유를 x-failure-reason 헤더에 넣을 수 있다(선택).
 * 피드백 Worker(ai_agents feedback_rabbit)는 안전한 오류 코드를 error_code 헤더에 넣는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterListener {

    static final String HEADER_REASON = "x-failure-reason";
    static final String HEADER_ERROR_CODE = "error_code";

    private final DeadLetterService deadLetterService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = Topology.EVALUATION_DLQ)
    public void onEvaluationDeadLetter(Message message) {
        JsonNode body = parse(message);
        requeueOnDbError(message, () -> deadLetterService.recordEvaluationFailure(body, reason(message)));
    }

    @RabbitListener(queues = Topology.LIVE_DLQ)
    public void onLiveEvaluationDeadLetter(Message message) {
        JsonNode body = parse(message);
        requeueOnDbError(message, () -> deadLetterService.recordLiveEvaluationFailure(body, reason(message)));
    }

    /** DLQ에는 다음 단계가 없어 그대로 버리면 실패가 기록되지 않는다. DB 오류는 잠시 쉬었다 다시 넣는다 */
    private static void requeueOnDbError(Message message, Runnable record) {
        try {
            record.run();
        } catch (DataAccessException | TransactionException e) {
            // 연결을 아예 못 얻으면 트랜잭션 시작에서 CannotCreateTransactionException(TransactionException)이 난다
            log.warn("DLQ message requeued after DB error: message_id={}, error={}",
                    message.getMessageProperties().getMessageId(), e.getClass().getSimpleName());
            EvaluationResultListener.pause();
            throw new ImmediateRequeueAmqpException("DB unavailable", e);
        }
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
        if (v == null) {
            v = message.getMessageProperties().getHeader(HEADER_ERROR_CODE);
        }
        return v == null ? null : v.toString();
    }
}
