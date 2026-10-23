package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** 업무 현황(내 업무) */
public record WorkStats(
        @Schema(example = "18") int total,
        @Schema(description = "단계별 업무 수 (WorkSummary.stage)") Map<String, Integer> stages,
        @Schema(description = "자료 보완이 필요한(자료 보류) 업무 수", example = "2") int needsMaterial,
        @Schema(description = "업무 유형별 평균 점수(10점 만점). 확인 대기·검토 대기·확정 평가의 최신 초안, 미관측 항목 제외")
        List<TypeScore> byType) {

    public record TypeScore(
            @Schema(example = "quote") String workType,
            @Schema(example = "견적서 작성") String label,
            @Schema(description = "업무 수", example = "4") int works,
            @Schema(description = "이번 주(월~일) 평균. 평가가 없으면 생략", example = "8.0") Double thisWeek,
            @Schema(description = "지난 주 평균", example = "7.2") Double lastWeek) {
    }
}
