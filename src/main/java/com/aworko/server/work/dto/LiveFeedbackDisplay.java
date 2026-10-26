package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 실시간 피드백 패널(LiveFeedbackPanel)이 그대로 쓰는 모양. 원본은 items·coaching */
public record LiveFeedbackDisplay(
        List<Item> items,
        @Schema(description = "코칭 한 줄(첫 제안의 다음 행동)", example = "다음 메시지에서 가격 정책 문서와 금액을 한 번 더 대조해보세요.")
        String coaching,
        @Schema(description = "그대로 보내 볼 수 있는 추천 프롬프트") String suggestedPrompt) {

    @Schema(name = "LiveFeedbackItem")
    public record Item(
            @Schema(description = "평가 기준 항목 이름. 기준이 없으면 잘한 점·보완할 점·확인 필요", example = "결과 검증") String label,
            @Schema(description = "good(양호) | warn(보완 필요) | pending(확인 필요)", example = "warn") String state,
            @Schema(description = "진단", example = "AI 결과를 참조 자료와 아직 대조하지 않았어요.") String note) {
    }
}
