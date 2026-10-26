package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 대화 1회의 질문과 답변. 기록 프록시가 응답을 보낸 뒤에 llm_exchanges에 저장하므로
 * messages 목록(seq 포함)에는 잠시 뒤에 나타난다.
 */
public record ChatTurnResponse(String question, @Schema(description = "AI 답변") String reply) {
}
