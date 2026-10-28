package com.aworko.server.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aworko.server.evaluation.Evaluation;
import com.aworko.server.evaluation.EvaluationRepository;
import com.aworko.server.evaluation.EvaluationStatus;
import com.aworko.server.support.TestJson;
import com.aworko.server.work.LiveEvaluation;
import com.aworko.server.work.LiveEvaluationRepository;
import com.aworko.server.work.LiveEvaluationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceTest {

    @Mock EvaluationRepository evaluations;
    @Mock EvaluationFailureRepository failures;
    @Mock LiveEvaluationRepository liveEvaluations;
    @Mock OutboxEventRepository outbox;
    DeadLetterService service;

    @BeforeEach
    void setUp() {
        service = new DeadLetterService(evaluations, failures, liveEvaluations, outbox);
    }

    @Test
    void evaluationDeadLetterRecordsFailureAndMarksEvaluationFailed() {
        Evaluation ev = evaluation();
        when(evaluations.findByIdForUpdate("EVAL-1")).thenReturn(Optional.of(ev));
        when(outbox.findEvaluateMessageIds("EVAL-1")).thenReturn(List.of("m-1"));
        var msg = TestJson.mapper().createObjectNode().put("message_id", "m-1").put("evaluation_id", "EVAL-1")
                .put("attempt", 3);

        service.recordEvaluationFailure(msg, "LLM timeout");

        ArgumentCaptor<EvaluationFailure> captor = ArgumentCaptor.forClass(EvaluationFailure.class);
        verify(failures).save(captor.capture());
        assertThat(captor.getValue().getEvaluationId()).isEqualTo("EVAL-1");
        assertThat(captor.getValue().getReason()).isEqualTo("LLM timeout");
        assertThat(captor.getValue().getMessage().get("attempt").asInt()).isEqualTo(3);
        assertThat(ev.getStatus()).isEqualTo(EvaluationStatus.FAILED);
    }

    @Test
    void missingReasonUsesDefault() {
        when(evaluations.findByIdForUpdate("EVAL-1")).thenReturn(Optional.of(evaluation()));

        service.recordEvaluationFailure(TestJson.mapper().createObjectNode().put("evaluation_id", "EVAL-1"), null);

        ArgumentCaptor<EvaluationFailure> captor = ArgumentCaptor.forClass(EvaluationFailure.class);
        verify(failures).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo(DeadLetterService.DEFAULT_REASON);
    }

    @Test
    void messageWithoutIdOrUnknownEvaluationIsDroppedWithoutWrites() {
        service.recordEvaluationFailure(TestJson.mapper().createObjectNode().put("message_id", "m"), null);
        when(evaluations.findByIdForUpdate("EVAL-X")).thenReturn(Optional.empty());
        service.recordEvaluationFailure(TestJson.mapper().createObjectNode().put("evaluation_id", "EVAL-X"), null);

        verify(failures, never()).save(any());
    }

    /** 재처리로 새 EVALUATE(m-2)를 보낸 뒤 이전 요청(m-1)의 실패가 늦게 오면, 진행 중인 새 실행을 실패로 바꾸지 않는다 */
    @Test
    void deadLetterOfEarlierRequestIsIgnored() {
        Evaluation ev = evaluation();
        when(evaluations.findByIdForUpdate("EVAL-1")).thenReturn(Optional.of(ev));
        when(outbox.findEvaluateMessageIds("EVAL-1")).thenReturn(List.of("m-2", "m-1"));

        service.recordEvaluationFailure(
                TestJson.mapper().createObjectNode().put("message_id", "m-1").put("evaluation_id", "EVAL-1"), "x");

        assertThat(ev.getStatus()).isEqualTo(EvaluationStatus.WAITING);
        verify(failures, never()).save(any());
    }

    /** Worker가 형식 오류로 만든 진단 메시지는 message_id가 새 값이라 아웃박스에 없다. 그래도 실패로 기록한다 */
    @Test
    void deadLetterWithUnknownMessageIdStillFails() {
        Evaluation ev = evaluation();
        when(evaluations.findByIdForUpdate("EVAL-1")).thenReturn(Optional.of(ev));
        when(outbox.findEvaluateMessageIds("EVAL-1")).thenReturn(List.of("m-2", "m-1"));

        service.recordEvaluationFailure(TestJson.mapper().createObjectNode().put("message_id", "diag")
                .put("type", "INVALID_FINAL_EVALUATE").put("evaluation_id", "EVAL-1"), "INVALID_EVENT");

        assertThat(ev.getStatus()).isEqualTo(EvaluationStatus.FAILED);
        verify(failures).save(any());
    }

    @Test
    void deadLetterAfterResultWasSavedLeavesStatusAndRecordsNothing() {
        Evaluation ev = evaluation();
        ReflectionTestUtils.setField(ev, "status", EvaluationStatus.AWAITING_CONFIRMATION);
        when(evaluations.findByIdForUpdate("EVAL-1")).thenReturn(Optional.of(ev));

        service.recordEvaluationFailure(TestJson.mapper().createObjectNode().put("evaluation_id", "EVAL-1"),
                "TOKEN_NOT_READY");

        assertThat(ev.getStatus()).isEqualTo(EvaluationStatus.AWAITING_CONFIRMATION);
        verify(failures, never()).save(any());
    }

    @Test
    void liveDeadLetterMarksRequestFailedWithReason() {
        LiveEvaluation live = LiveEvaluation.request("W-1", "E-1021", 1, 5, null, null, OffsetDateTime.now());
        when(liveEvaluations.findById(live.getLiveEvalId())).thenReturn(Optional.of(live));

        service.recordLiveEvaluationFailure(
                TestJson.mapper().createObjectNode().put("live_eval_id", live.getLiveEvalId()), "MCP 오류");

        assertThat(live.getStatus()).isEqualTo(LiveEvaluationStatus.FAILED);
        assertThat(live.getError()).isEqualTo("MCP 오류");
    }

    private static Evaluation evaluation() {
        return Evaluation.create("EVAL-1", "W-1", "C-1", "ART-1", "v2", "v2", "W-1:v2:v2");
    }
}
