package com.aworko.server.work;

import io.swagger.v3.oas.annotations.media.Schema;

public record WorkEventResponse(
        @Schema(example = "EVAL-E2E-QT-08-v2") String evaluationId,
        @Schema(description = "false면 같은 완료 이벤트로 이미 만들어진 평가 건") boolean created,
        @Schema(example = "대기") String status) {
}
