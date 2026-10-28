package com.aworko.server.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EvaluationStatusRulesTest {

    @Test
    void displayLabelGroupsProgressStagesAsEvaluating() {
        assertThat(EvaluationStatus.WAITING.displayLabel()).isEqualTo("평가 중");
        assertThat(EvaluationStatus.COLLECTING.displayLabel()).isEqualTo("평가 중");
        assertThat(EvaluationStatus.COACHING.displayLabel()).isEqualTo("평가 중");
        assertThat(EvaluationStatus.AWAITING_CONFIRMATION.displayLabel()).isEqualTo("확인 대기");
        assertThat(EvaluationStatus.AWAITING_REVIEW.displayLabel()).isEqualTo("검토 중");
        assertThat(EvaluationStatus.CONFIRMED.displayLabel()).isEqualTo("확정");
        assertThat(EvaluationStatus.ON_HOLD.displayLabel()).isEqualTo("보류");
        assertThat(EvaluationStatus.FAILED.displayLabel()).isEqualTo("실패");
    }

    @Test
    void transitionOnlyFromAllowedStatusElse409() {
        Evaluation e = Evaluation.create("E", "W", "C", "A", "v2", "v2", "k");
        ReflectionTestUtils.setField(e, "status", EvaluationStatus.AWAITING_CONFIRMATION);

        e.transition(Set.of(EvaluationStatus.AWAITING_CONFIRMATION), EvaluationStatus.CONFIRMED);
        assertThat(e.getStatus()).isEqualTo(EvaluationStatus.CONFIRMED);

        assertThatThrownBy(() -> e.transition(Set.of(EvaluationStatus.AWAITING_CONFIRMATION),
                EvaluationStatus.CONFIRMED))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void retryResetsFailedOnHoldAndStuckEvaluationsOnly() {
        OffsetDateTime staleBefore = OffsetDateTime.now().minusMinutes(30);
        for (EvaluationStatus ok : new EvaluationStatus[]{EvaluationStatus.FAILED, EvaluationStatus.ON_HOLD}) {
            Evaluation e = Evaluation.create("E", "W", "C", "A", "v2", "v2", "k");
            ReflectionTestUtils.setField(e, "status", ok);
            e.resetForRetry(staleBefore);
            assertThat(e.getStatus()).isEqualTo(EvaluationStatus.WAITING);
        }

        Evaluation stuck = Evaluation.create("E", "W", "C", "A", "v2", "v2", "k");
        ReflectionTestUtils.setField(stuck, "updatedAt", staleBefore.minusMinutes(1));
        stuck.resetForRetry(staleBefore);
        assertThat(stuck.getStatus()).isEqualTo(EvaluationStatus.WAITING);
        assertThat(stuck.getUpdatedAt()).isAfter(staleBefore);

        Evaluation running = Evaluation.create("E", "W", "C", "A", "v2", "v2", "k");
        assertThatThrownBy(() -> running.resetForRetry(staleBefore))
                .isInstanceOf(BusinessException.class).hasMessageContaining("진행 중");
        for (EvaluationStatus no : new EvaluationStatus[]{EvaluationStatus.AWAITING_CONFIRMATION,
                EvaluationStatus.AWAITING_REVIEW, EvaluationStatus.CONFIRMED}) {
            Evaluation e = Evaluation.create("E", "W", "C", "A", "v2", "v2", "k");
            ReflectionTestUtils.setField(e, "status", no);
            assertThatThrownBy(() -> e.resetForRetry(staleBefore))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(e.getStatus()).isEqualTo(no);
        }
    }

    @Test
    void idRuleMatchesBusinessMcp() {
        assertThat(Evaluation.idFor("W-E2E-QT-08", "v2")).isEqualTo("EVAL-E2E-QT-08-v2");
        assertThat(Evaluation.idFor("CUSTOM-1", "v1")).isEqualTo("EVAL-CUSTOM-1-v1");
        assertThat(Evaluation.defaultSourceKey("W-1", "v2", "v3")).isEqualTo("W-1:v2:v3");
    }
}
