package com.aworko.server.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.aworko.server.evaluation.Evaluation;
import com.aworko.server.evaluation.EvaluationStatus;
import com.aworko.server.work.LiveEvaluation;
import com.aworko.server.work.LiveEvaluationStatus;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** DLQ로 인한 실패 표시가 이미 끝난 상태를 덮어쓰지 않는지. */
class StatusFailureRulesTest {

    @Test
    void evaluationMarkFailedDoesNotOverrideConfirmed() {
        Evaluation ev = Evaluation.create("EVAL-1", "W-1", "C-1", "ART-1", "v2", "v2", "k");
        assertThat(ev.markFailed()).isTrue();
        assertThat(ev.getStatus()).isEqualTo(EvaluationStatus.FAILED);
        assertThat(ev.markFailed()).isFalse();

        Evaluation confirmed = Evaluation.create("EVAL-2", "W-1", "C-1", "ART-1", "v2", "v2", "k2");
        ReflectionTestUtils.setField(confirmed, "status", EvaluationStatus.CONFIRMED);
        assertThat(confirmed.markFailed()).isFalse();
        assertThat(confirmed.getStatus()).isEqualTo(EvaluationStatus.CONFIRMED);
    }

    /** 결과가 저장된 뒤 늦게 다시 온 메시지가 DLQ로 가도, 직원 확인·팀장 검토 중인 건을 처리 실패로 바꾸지 않는다 */
    @Test
    void evaluationMarkFailedOnlyWhileInProgress() {
        for (EvaluationStatus done : new EvaluationStatus[]{EvaluationStatus.AWAITING_CONFIRMATION,
                EvaluationStatus.AWAITING_REVIEW, EvaluationStatus.ON_HOLD}) {
            Evaluation ev = Evaluation.create("EVAL-3", "W-1", "C-1", "ART-1", "v2", "v2", "k3");
            ReflectionTestUtils.setField(ev, "status", done);
            assertThat(ev.markFailed()).isFalse();
            assertThat(ev.getStatus()).isEqualTo(done);
        }
        Evaluation collecting = Evaluation.create("EVAL-4", "W-1", "C-1", "ART-1", "v2", "v2", "k4");
        ReflectionTestUtils.setField(collecting, "status", EvaluationStatus.COLLECTING);
        assertThat(collecting.markFailed()).isTrue();
    }

    @Test
    void liveEvaluationMarkFailedDoesNotOverrideCompleted() {
        LiveEvaluation live = LiveEvaluation.request("W-1", "E-1", 1, 5, null, null, OffsetDateTime.now());
        assertThat(live.getStatus()).isEqualTo(LiveEvaluationStatus.WAITING);
        assertThat(live.markFailed("x")).isTrue();
        assertThat(live.markFailed("y")).isFalse();
        assertThat(live.getError()).isEqualTo("x");

        LiveEvaluation done = LiveEvaluation.request("W-1", "E-1", 1, 5, null, null, OffsetDateTime.now());
        ReflectionTestUtils.setField(done, "status", LiveEvaluationStatus.COMPLETED);
        assertThat(done.markFailed("late")).isFalse();
        assertThat(done.getStatus()).isEqualTo(LiveEvaluationStatus.COMPLETED);
    }
}
