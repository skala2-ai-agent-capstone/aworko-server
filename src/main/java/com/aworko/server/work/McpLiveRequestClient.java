package com.aworko.server.work;

import com.aworko.server.global.config.McpProperties;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * business-mcp POST /internal/live-evaluations 호출.
 * 응답은 {ok:true, data:{live_eval_id, from_seq, to_seq, rubric_version …}} 또는 {ok:false, error:{code, message}}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(McpProperties.class)
public class McpLiveRequestClient implements LiveRequestGateway {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public McpLiveRequestClient(RestClient.Builder builder, McpProperties properties, ObjectMapper objectMapper) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        this.client = builder.baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Key", properties.internalKey())
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public LiveRequest create(String workId, String requestedBy) {
        JsonNode body;
        try {
            body = client.post().uri("/internal/live-evaluations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("work_id", workId, "requested_by", requestedBy))
                    .exchange((request, response) -> parse(response.getBody().readAllBytes()));
        } catch (RestClientException e) {
            // 연결 실패·시간 초과. 원인 메시지에 내부 주소가 섞일 수 있어 종류만 남긴다
            log.error("business-mcp live request failed: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "피드백 서버에 연결할 수 없습니다");
        }
        if (body == null) {
            log.error("business-mcp live request returned a non-JSON response");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "피드백 서버 응답을 읽을 수 없습니다");
        }
        if (!body.path("ok").asBoolean(false)) {
            JsonNode error = body.path("error");
            throw new Rejected(error.path("code").asText("UNKNOWN"), error.path("message").asText(""));
        }
        JsonNode data = body.path("data");
        return new LiveRequest(data.path("live_eval_id").asText(), data.path("from_seq").asInt(),
                data.path("to_seq").asInt(), data.hasNonNull("rubric_version") ? data.get("rubric_version").asText() : null);
    }

    private JsonNode parse(byte[] raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            return null;
        }
    }
}
