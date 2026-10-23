package com.aworko.server.support;

import com.aworko.server.work.LlmChatGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** llm-proxy 대역. 받은 대화를 기억하고, 기록 프록시처럼 llm_exchanges에 남긴 뒤 "답변: {질문}"을 돌려준다. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeLlmChat {

    @Bean
    @Primary
    Recorder fakeLlmChatGateway(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new Recorder(jdbc, objectMapper);
    }

    public static class Recorder implements LlmChatGateway {

        public final List<List<Map<String, String>>> calls = new CopyOnWriteArrayList<>();
        private final JdbcTemplate jdbc;
        private final ObjectMapper objectMapper;

        Recorder(JdbcTemplate jdbc, ObjectMapper objectMapper) {
            this.jdbc = jdbc;
            this.objectMapper = objectMapper;
        }

        @Override
        public String reply(String workId, String userId, List<Map<String, String>> messages) {
            calls.add(messages);
            String answer = "답변: " + messages.get(messages.size() - 1).get("content");
            try {
                jdbc.update("INSERT INTO llm_exchanges (work_id, user_id, stage, status_code, request, response) "
                                + "VALUES (?, ?, 'workspace_chat', 200, ?::jsonb, ?::jsonb)", workId, userId,
                        objectMapper.writeValueAsString(Map.of("messages", messages)),
                        objectMapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", answer))))));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return answer;
        }

        public List<Map<String, String>> last() {
            return calls.get(calls.size() - 1);
        }
    }
}
