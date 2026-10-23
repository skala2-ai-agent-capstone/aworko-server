package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 업무 유형과 「새 업무 생성」에서 받을 등록 항목. 항목 key는 평가(문제 정의)가 읽는 registration 키와 같다. */
public record WorkTypeInfo(
        @Schema(example = "rfp_analysis") String workType,
        @Schema(example = "RFP 요구사항 분석") String label,
        @Schema(description = "산출물 이름", example = "요구사항 대응표") String artifactTitle,
        List<Field> registrationFields) {

    public record Field(@Schema(example = "submission_deadline") String key, @Schema(example = "제출 기한") String label) {
    }
}
