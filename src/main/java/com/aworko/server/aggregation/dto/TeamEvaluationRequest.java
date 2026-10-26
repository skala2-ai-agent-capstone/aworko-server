package com.aworko.server.aggregation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** 팀 평가 요청 기간. 팀 대시보드와 같이 끝 날짜를 포함한다(최대 93일) */
public record TeamEvaluationRequest(
        @Schema(example = "2026-10-19") @NotNull LocalDate from,
        @Schema(example = "2026-10-25") @NotNull LocalDate to) {
}
