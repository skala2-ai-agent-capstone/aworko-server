package com.aworko.server.settings;

import com.aworko.server.global.config.McpProperties;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.settings.dto.PolicyDocumentSaved;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * business-mcp POST /internal/documents 호출. 파일은 base64로 보낸다.
 * PDF 분할·임베딩에 시간이 걸려 읽기 제한은 app.mcp.document-timeout(기본 120초)으로 따로 둔다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(McpProperties.class)
public class McpPolicyDocumentClient implements PolicyDocumentGateway {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public McpPolicyDocumentClient(RestClient.Builder builder, McpProperties properties,
                                   @Value("${app.mcp.document-timeout:120s}") Duration documentTimeout,
                                   ObjectMapper objectMapper) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(documentTimeout);
        this.client = builder.baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Key", properties.internalKey())
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public PolicyDocumentSaved upload(PolicyUpload upload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("company_id", upload.companyId());
        body.put("version", upload.version());
        body.put("source_file", upload.sourceFile());
        body.put("content_base64", Base64.getEncoder().encodeToString(upload.content()));
        if (upload.title() != null) {
            body.put("title", upload.title());
        }
        if (upload.effectiveFrom() != null) {
            body.put("effective_from", upload.effectiveFrom().toString());
        }
        if (upload.uploadedBy() != null) {
            body.put("uploaded_by", upload.uploadedBy());
        }
        JsonNode d = post(body);
        JsonNode diff = d.path("diff");
        return new PolicyDocumentSaved(d.path("doc_id").asText(), d.path("version").asText(),
                d.path("title").asText(null), d.path("status").asText(null), d.path("chunks").asInt(),
                d.path("clauses").asInt(), texts(d.path("retired_docs")),
                diff.isObject() ? new PolicyDocumentSaved.Diff(diff.path("base").asText(null), texts(diff.path("added")),
                        texts(diff.path("removed")), texts(diff.path("changed"))) : null,
                d.path("duplicate").asBoolean(false), d.path("rag_reloaded").asBoolean(false));
    }

    private JsonNode post(Map<String, Object> payload) {
        JsonNode body;
        try {
            body = client.post().uri("/internal/documents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> parse(response.getBody().readAllBytes()));
        } catch (RestClientException e) {
            // 연결 실패·시간 초과. 원인 메시지에 내부 주소가 섞일 수 있어 종류만 남긴다
            log.error("business-mcp document upload failed: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "내규 저장 서버에 연결할 수 없습니다");
        }
        if (body == null) {
            log.error("business-mcp document upload returned a non-JSON response");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "내규 저장 서버 응답을 읽을 수 없습니다");
        }
        if (!body.path("ok").asBoolean(false)) {
            JsonNode error = body.path("error");
            throw new RubricGateway.Rejected(error.path("code").asText("UNKNOWN"), error.path("message").asText(""));
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

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }
}
