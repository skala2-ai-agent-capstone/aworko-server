package com.aworko.server.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AppealRequest(
        @Schema(description = "이의 제기할 항목 ID", example = "[\"quote.3\"]") @NotEmpty List<@NotBlank String> targetItems,
        @Schema(description = "이의 사유", example = "할인 승인 요청 기록을 근거로 확인해 주세요") @NotBlank @Size(max = 2000)
        String comment,
        @Schema(description = "근거로 지정한 기존 업무 근거 ID(선택)") List<String> citedEvidence) {
}
