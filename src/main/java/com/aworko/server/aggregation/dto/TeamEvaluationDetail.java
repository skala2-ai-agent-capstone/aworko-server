package com.aworko.server.aggregation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

public record TeamEvaluationDetail(
        TeamEvaluationSummary evaluation,
        @Schema(description = "팀 리포트(TeamReport: summary·metrics·strengths·improvements). 개인 이름·평가 건 ID는 없다. "
                + "완료 전이면 null", nullable = true) JsonNode report) {
}
