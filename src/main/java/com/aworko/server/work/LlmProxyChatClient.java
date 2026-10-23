package com.aworko.server.work;

import com.aworko.server.global.config.LlmProperties;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** llm-proxy POST /v1/chat/completions (스트리밍 없이 한 번에 받는다). */
@Slf4j
@Component
@EnableConfigurationProperties(LlmProperties.class)
public class LlmProxyChatClient implements LlmChatGateway {

    static final String STAGE = "workspace_chat";

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String model;

    public LlmProxyChatClient(RestClient.Builder builder, LlmProperties properties, ObjectMapper objectMapper) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(properties.timeout());
        this.client = builder.baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .build();
        this.objectMapper = objectMapper;
        this.model = properties.model();
    }

    @Override
    public String reply(String workId, String userId, List<Map<String, String>> messages) {
        Map<String, Object> body = Map.of("model", model, "messages", messages, "stream", false,
                "temperature", 0.3, "max_tokens", 2048);
        Result result;
        try {
            result = client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Stage", STAGE)
                    .header("X-Work-Id", workId)
                    .header("X-User-Id", userId)
                    .body(body)
                    .exchange((request, response) -> new Result(response.getStatusCode().value(),
                            parse(response.getBody().readAllBytes())));
        } catch (RestClientException e) {
            log.error("llm-proxy call failed: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
        JsonNode content = result.body() == null ? null
                : result.body().path("choices").path(0).path("message").path("content");
        if (result.status() / 100 != 2 || content == null || !content.isTextual()) {
            // 프록시 오류 코드(invalid_trace_headers, upstream_timeout …)만 남기고 본문은 로그에 쓰지 않는다
            log.error("llm-proxy returned status={} error={}", result.status(),
                    result.body() == null ? null : result.body().path("error").asText(null));
            throw unavailable();
        }
        return content.asText();
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 응답을 받지 못했어요. 잠시 후 다시 보내 주세요");
    }

    private JsonNode parse(byte[] raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            return null;
        }
    }

    private record Result(int status, JsonNode body) {
    }
}
