package com.aworko.server.work;

import java.util.List;
import java.util.Map;

/**
 * 업무 공간 대화 1회 호출. 구현은 llm-proxy로 보내고(X-Stage: workspace_chat, X-Work-Id, X-User-Id),
 * 프록시가 호출을 llm_exchanges에 기록한다. 백엔드는 대화를 따로 저장하지 않는다.
 */
@FunctionalInterface
public interface LlmChatGateway {

    /**
     * @param messages OpenAI chat 형식 [{role, content}]: system → 이전 대화 → 이번 질문(마지막 user)
     * @return 답변 본문
     */
    String reply(String workId, String userId, List<Map<String, String>> messages);
}
