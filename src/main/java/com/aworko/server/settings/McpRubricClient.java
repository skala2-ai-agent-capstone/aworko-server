package com.aworko.server.settings;

import com.aworko.server.global.config.McpProperties;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.settings.dto.RubricVersionChange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * business-mcp /internal/rubrics/* 호출.
 * 응답은 {ok:true, data:{created|approved|discarded:[…]} 또는 {item, changed_fields}} 또는 {ok:false, error:{code, message}}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(McpProperties.class)
public class McpRubricClient implements RubricGateway {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public McpRubricClient(RestClient.Builder builder, McpProperties properties, ObjectMapper objectMapper) {
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
    public List<RubricVersionChange> createDrafts(String companyId, List<String> workTypes, String createdBy) {
        Map<String, Object> body = target(companyId, workTypes);
        body.put("created_by", createdBy);
        return changes(post("/internal/rubrics/drafts", body).path("created"));
    }

    @Override
    public List<String> updateItem(String companyId, String workType, String item, Map<String, Object> fields,
                                   String editedBy) {
        Map<String, Object> body = target(companyId, null);
        body.put("work_type", workType);
        body.put("item", item);
        body.put("fields", fields);
        body.put("edited_by", editedBy);
        return texts(post("/internal/rubrics/drafts/items", body).path("changed_fields"));
    }

    @Override
    public List<RubricVersionChange> approve(String companyId, List<String> workTypes, String approvedBy) {
        Map<String, Object> body = target(companyId, workTypes);
        body.put("approved_by", approvedBy);
        return changes(post("/internal/rubrics/approve", body).path("approved"));
    }

    @Override
    public List<RubricVersionChange> discard(String companyId, List<String> workTypes) {
        return changes(post("/internal/rubrics/discard", target(companyId, workTypes)).path("discarded"));
    }

    /** work_types는 비우면 MCP 기본값을 쓰도록 아예 보내지 않는다 */
    private static Map<String, Object> target(String companyId, List<String> workTypes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("company_id", companyId);
        if (workTypes != null) {
            body.put("work_types", workTypes);
        }
        return body;
    }

    private JsonNode post(String path, Map<String, Object> payload) {
        JsonNode body;
        try {
            body = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> parse(response.getBody().readAllBytes()));
        } catch (RestClientException e) {
            // 연결 실패·시간 초과. 원인 메시지에 내부 주소가 섞일 수 있어 종류만 남긴다
            log.error("business-mcp rubric request failed: path={} {}", path, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "평가 기준 서버에 연결할 수 없습니다");
        }
        if (body == null) {
            log.error("business-mcp rubric request returned a non-JSON response: path={}", path);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "평가 기준 서버 응답을 읽을 수 없습니다");
        }
        if (!body.path("ok").asBoolean(false)) {
            JsonNode error = body.path("error");
            throw new Rejected(error.path("code").asText("UNKNOWN"), error.path("message").asText(""));
        }
        return body.path("data");
    }

    private JsonNode parse(byte[] raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<RubricVersionChange> changes(JsonNode rows) {
        List<RubricVersionChange> out = new ArrayList<>();
        for (JsonNode r : rows) {
            out.add(new RubricVersionChange(r.path("work_type").asText(), r.path("doc_id").asText(),
                    r.path("version").asText(),
                    r.hasNonNull("based_on_version") ? r.get("based_on_version").asText() : null,
                    r.has("retired_versions") ? texts(r.get("retired_versions")) : null));
        }
        return out;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }
}
