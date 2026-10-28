package com.aworko.server.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.aworko.server.support.TestJson;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

/** DLQ에는 다음 단계가 없어, DB 오류로 기록하지 못한 메시지는 버리지 않고 다시 큐에 넣는다. */
@ExtendWith(MockitoExtension.class)
class DeadLetterListenerTest {

    @Mock DeadLetterService service;

    private final Message message = MessageBuilder
            .withBody("{\"evaluation_id\":\"EVAL-1\"}".getBytes(StandardCharsets.UTF_8)).build();

    @Test
    void requeuesWhenTheDatabaseCannotBeReached() {
        // 연결을 아예 못 얻으면 트랜잭션 시작에서 DataAccessException이 아닌 TransactionException이 난다
        doThrow(new CannotCreateTransactionException("no connection"))
                .when(service).recordEvaluationFailure(any(), any());

        assertThatThrownBy(() -> listener().onEvaluationDeadLetter(message))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
        verify(service).recordEvaluationFailure(any(), any());
    }

    @Test
    void requeuesOnSqlErrors() {
        doThrow(new QueryTimeoutException("slow")).when(service).recordLiveEvaluationFailure(any(), any());

        assertThatThrownBy(() -> listener().onLiveEvaluationDeadLetter(message))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
    }

    private DeadLetterListener listener() {
        return new DeadLetterListener(service, TestJson.mapper());
    }
}
