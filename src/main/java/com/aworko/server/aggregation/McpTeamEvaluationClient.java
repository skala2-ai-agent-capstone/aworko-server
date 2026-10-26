package com.aworko.server.aggregation;

import com.aworko.server.global.config.McpProperties;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * business-mcp POST /internal/team-evaluations 호출.
 * 응답은 {ok:true, data:{request_id, release_approved, min_members, created …}} 또는 {ok:false, error:{code, message}}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(McpProperties.class)
public class McpTeamEvaluationClient implements TeamEvaluationGateway {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public McpTeamEvaluationClient(RestClient.Builder builder, McpProperties properties, ObjectMapper objectMapper) {
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
    public Created create(String companyId, String teamId, LocalDate periodStart, LocalDate periodEnd,
                          boolean releaseApproved, String requestId, String requestedBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("company_id", companyId);
        body.put("team_id", teamId);
        body.put("period_start", periodStart.toString());
        body.put("period_end", periodEnd.toString());
        body.put("release_approved", releaseApproved);
        body.put("request_id", requestId);
        body.put("requested_by", requestedBy);
        JsonNode reply;
        try {
            reply = client.post().uri("/internal/team-evaluations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> parse(response.getBody().readAllBytes()));
        } catch (RestClientException e) {
            // 원인 메시지에 내부 주소가 섞일 수 있어 종류만 남긴다
            log.error("business-mcp team evaluation request failed: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "팀 평가 서버에 연결할 수 없습니다");
        }
        if (reply == null) {
            log.error("business-mcp team evaluation request returned a non-JSON response");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "팀 평가 서버 응답을 읽을 수 없습니다");
        }
        if (!reply.path("ok").asBoolean(false)) {
            JsonNode error = reply.path("error");
            throw new Rejected(error.path("code").asText("UNKNOWN"), error.path("message").asText(""));
        }
        JsonNode data = reply.path("data");
        return new Created(data.path("request_id").asText(), data.path("release_approved").asBoolean(),
                data.path("min_members").asInt(), data.path("created").asBoolean());
    }

    private JsonNode parse(byte[] raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            return null;
        }
    }
}
