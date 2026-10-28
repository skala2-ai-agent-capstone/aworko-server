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
 * evaluation.results 소비. 형식이 깨진 알림은 버린다(다시 받아도 처리할 수 없다).
 * DB 오류는 알림을 잃지 않도록 잠시 쉬었다가 다시 큐에 넣는다(기본 설정은 예외 시 버림이라 명시적으로 재전달).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationResultListener {

    static final long REQUEUE_PAUSE_MS = 1000;

    private final EvaluationResultService resultService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = Topology.EVALUATION_RESULTS)
    public void onFinalEvaluationResult(Message message) {
        String messageId = message.getMessageProperties().getMessageId();
        EvaluationResultService.Saved saved = EvaluationResultService.Saved.parse(parse(message))
                .orElseThrow(() -> {
                    log.warn("Invalid final evaluation result dropped: message_id={}", messageId);
                    return new AmqpRejectAndDontRequeueException("Invalid final evaluation result");
                });
        try {
            resultService.apply(saved);
        } catch (DataAccessException | TransactionException e) {
            // 연결을 아예 못 얻으면 트랜잭션 시작에서 CannotCreateTransactionException(TransactionException)이 난다
            log.warn("Final evaluation result requeued after DB error: message_id={}, error={}", messageId,
                    e.getClass().getSimpleName());
            pause();
            throw new ImmediateRequeueAmqpException("DB unavailable", e);
        }
    }

    private JsonNode parse(Message message) {
        try {
            return objectMapper.readTree(message.getBody());
        } catch (IOException e) {
            return null;
        }
    }

    static void pause() {
        try {
            Thread.sleep(REQUEUE_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
