package com.aworko.server.runtoken;

import com.aworko.server.global.config.McpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * business-mcp 실행 토큰 발급: POST /internal/runs(최종 평가, evaluation_id), POST /internal/team-runs(팀 평가, request_id).
 * 응답은 {ok:true, data:{run_token, run_id …}} 또는 {ok:false, error:{code, message}}.
 * 만료 시각은 요청 직전 시각 + ttl로 잡는다(MCP가 넣는 exp보다 늦지 않다).
 */
@Slf4j
@Component
@EnableConfigurationProperties(McpProperties.class)
public class McpRunTokenClient implements RunTokenGateway, TeamRunTokenGateway {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public McpRunTokenClient(RestClient.Builder builder, McpProperties properties, ObjectMapper objectMapper,
                             Clock clock) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        this.client = builder.baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Key", properties.internalKey())
                .build();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public IssuedToken issue(String evaluationId, Duration ttl) {
        return post("/internal/runs", Map.of("evaluation_id", evaluationId, "ttl_sec", ttl.toSeconds()), ttl);
    }

    @Override
    public IssuedToken issueTeam(String requestId, Duration ttl) {
        return post("/internal/team-runs", Map.of("request_id", requestId, "ttl_sec", ttl.toSeconds()), ttl);
    }

    private IssuedToken post(String uri, Map<String, Object> request, Duration ttl) {
        OffsetDateTime requestedAt = OffsetDateTime.now(clock);
        JsonNode body;
        try {
            body = client.post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, response) -> parse(response.getBody().readAllBytes()));
        } catch (RestClientException e) {
            // 원인 메시지에 내부 주소가 섞일 수 있어 종류만 남긴다
            throw new Unavailable(e.getClass().getSimpleName());
        }
        if (body == null) {
            throw new Unavailable("non-JSON response");
        }
        if (!body.path("ok").asBoolean(false)) {
            JsonNode error = body.path("error");
            throw new Rejected(error.path("code").asText("UNKNOWN"), error.path("message").asText(""));
        }
        String token = body.path("data").path("run_token").asText("");
        if (token.isBlank()) {
            throw new Unavailable("response without run_token");
        }
        return new IssuedToken(token, requestedAt.plus(ttl));
    }

    private JsonNode parse(byte[] raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            return null;
        }
    }
}
