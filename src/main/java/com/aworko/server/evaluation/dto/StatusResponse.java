package com.aworko.server.evaluation.dto;

import com.aworko.server.evaluation.Evaluation;
import io.swagger.v3.oas.annotations.media.Schema;

/** 확인·이의·검토·재처리 같은 행동의 결과 */
public record StatusResponse(
        @Schema(example = "EVAL-E2E-QT-08-v2") String evaluationId,
        @Schema(example = "확정") String status,
        @Schema(example = "확정") String displayStatus) {

    public static StatusResponse of(Evaluation e) {
        return new StatusResponse(e.getEvaluationId(), e.getStatus().getCode(), e.getStatus().displayLabel());
    }
}
