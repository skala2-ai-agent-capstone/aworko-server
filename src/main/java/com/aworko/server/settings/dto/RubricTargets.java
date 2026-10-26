package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 초안 만들기·승인·폐기 대상 업무 유형. 본문이나 work_types를 비우면 만들기는 4개 유형 모두, 승인·폐기는 모든 초안 */
public record RubricTargets(
        @Schema(description = "rfp_analysis | proposal | quote | inquiry_reply", example = "[\"quote\"]")
        @Size(min = 1, max = 4) List<String> workTypes) {
}
