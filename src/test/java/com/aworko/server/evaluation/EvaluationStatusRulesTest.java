package com.aworko.server.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
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
    void idRuleMatchesBusinessMcp() {
        assertThat(Evaluation.idFor("W-E2E-QT-08", "v2")).isEqualTo("EVAL-E2E-QT-08-v2");
        assertThat(Evaluation.idFor("CUSTOM-1", "v1")).isEqualTo("EVAL-CUSTOM-1-v1");
        assertThat(Evaluation.defaultSourceKey("W-1", "v2", "v3")).isEqualTo("W-1:v2:v3");
    }
}
