package com.aworko.server.work;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record WorkEventRequest(
        @Schema(description = "업무 ID", example = "W-E2E-QT-08") @NotBlank String taskId,
        @Schema(description = "최종 산출물 버전", example = "v2") @NotBlank String deliverableVersion,
        @Schema(description = "중복 방지 키(선택). 없으면 {업무}:{버전}:{루브릭 버전}") String idempotencyKey) {
}
