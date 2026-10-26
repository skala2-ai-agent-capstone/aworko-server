package com.aworko.server.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aworko.server.global.config.McpProperties;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.settings.dto.RubricVersionChange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** business-mcp /internal/rubrics/* 요청 본문과 응답 봉투({ok, data} / {ok:false, error})를 실제 HTTP로 확인한다. */
class McpRubricClientTest {

    HttpServer server;
    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final AtomicReference<String> seenPath = new AtomicReference<>();
    final AtomicReference<String> seenKey = new AtomicReference<>();
    final AtomicReference<String> seenBody = new AtomicReference<>();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/rubrics", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    McpRubricClient client() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new McpRubricClient(RestClient.builder(), new McpProperties(url, "k-123", Duration.ofSeconds(2)),
                new ObjectMapper());
    }

    JsonNode body() throws IOException {
        return json.readTree(seenBody.get());
    }

    @Test
    void createDraftsOmitsWorkTypesForAllAndReadsCreatedVersions() throws IOException {
        reply.set("{\"ok\":true,\"data\":{\"created\":[{\"work_type\":\"quote\",\"doc_id\":\"RUB-quote\","
                + "\"version\":\"v3\",\"based_on_version\":\"v2\"}]}}");

        List<RubricVersionChange> created = client().createDrafts("C-1", null, "가상 팀장");

        assertThat(created).containsExactly(new RubricVersionChange("quote", "RUB-quote", "v3", "v2", null));
        assertThat(seenPath.get()).isEqualTo("/internal/rubrics/drafts");
        assertThat(seenKey.get()).isEqualTo("k-123");
        assertThat(body().has("work_types")).isFalse();
        assertThat(body().path("company_id").asText()).isEqualTo("C-1");
        assertThat(body().path("created_by").asText()).isEqualTo("가상 팀장");
    }

    @Test
    void updateItemSendsMcpFieldNamesAndReturnsChangedFields() throws IOException {
        reply.set("{\"ok\":true,\"data\":{\"work_type\":\"quote\",\"doc_id\":\"RUB-quote\",\"version\":\"v3\","
                + "\"item\":{\"item\":\"문제 정의\"},\"changed_fields\":[\"score_2\"]}}");

        List<String> changed = client().updateItem("C-1", "quote", "문제 정의",
                Map.of("score_2", "모두 기재", "related_clauses", List.of("ART-11")), "가상 팀장");

        assertThat(changed).containsExactly("score_2");
        assertThat(seenPath.get()).isEqualTo("/internal/rubrics/drafts/items");
        JsonNode b = body();
        assertThat(b.path("work_type").asText()).isEqualTo("quote");
        assertThat(b.path("item").asText()).isEqualTo("문제 정의");
        assertThat(b.path("fields").path("score_2").asText()).isEqualTo("모두 기재");
        assertThat(b.path("fields").path("related_clauses").get(0).asText()).isEqualTo("ART-11");
        assertThat(b.path("edited_by").asText()).isEqualTo("가상 팀장");
    }

    @Test
    void approveAndDiscardPassWorkTypesAndReadTheirLists() throws IOException {
        reply.set("{\"ok\":true,\"data\":{\"approved\":[{\"work_type\":\"quote\",\"doc_id\":\"RUB-quote\","
                + "\"version\":\"v3\",\"retired_versions\":[\"v2\"]}]}}");
        assertThat(client().approve("C-1", List.of("quote"), "가상 팀장"))
                .containsExactly(new RubricVersionChange("quote", "RUB-quote", "v3", null, List.of("v2")));
        assertThat(seenPath.get()).isEqualTo("/internal/rubrics/approve");
        assertThat(body().path("work_types").get(0).asText()).isEqualTo("quote");
        assertThat(body().path("approved_by").asText()).isEqualTo("가상 팀장");

        reply.set("{\"ok\":true,\"data\":{\"discarded\":[{\"work_type\":\"quote\",\"doc_id\":\"RUB-quote\",\"version\":\"v3\"}]}}");
        assertThat(client().discard("C-1", null))
                .containsExactly(new RubricVersionChange("quote", "RUB-quote", "v3", null, null));
        assertThat(seenPath.get()).isEqualTo("/internal/rubrics/discard");
    }

    @Test
    void errorEnvelopeBecomesRejectedWithMcpCode() {
        status.set(400);
        reply.set("{\"ok\":false,\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"#1 문제 정의: 현행 내규에 없는 조항 ['ART-99']\"}}");

        assertThatThrownBy(() -> client().updateItem("C-1", "quote", "문제 정의", Map.of("related_clauses", List.of("ART-99")), "x"))
                .isInstanceOf(RubricGateway.Rejected.class)
                .hasMessageContaining("ART-99")
                .extracting(e -> ((RubricGateway.Rejected) e).code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void nonJsonOrUnreachableServerIsServiceUnavailable() {
        status.set(502);
        reply.set("<html>Bad Gateway</html>");
        assertThatThrownBy(() -> client().approve("C-1", null, "x"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);

        McpRubricClient unreachable = new McpRubricClient(RestClient.builder(),
                new McpProperties("http://127.0.0.1:1", "k", Duration.ofSeconds(1)), new ObjectMapper());
        assertThatThrownBy(() -> unreachable.discard("C-1", null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);
    }
}
