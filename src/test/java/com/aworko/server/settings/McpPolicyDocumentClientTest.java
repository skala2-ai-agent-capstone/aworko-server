package com.aworko.server.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aworko.server.global.config.McpProperties;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.settings.dto.PolicyDocumentSaved;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** business-mcp /internal/documents 요청 본문(base64·선택 칸 생략)과 응답 봉투를 실제 HTTP로 확인한다. */
class McpPolicyDocumentClientTest {

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
        server.createContext("/internal/documents", exchange -> {
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

    McpPolicyDocumentClient client() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new McpPolicyDocumentClient(RestClient.builder(), new McpProperties(url, "k-123", Duration.ofSeconds(2)),
                Duration.ofSeconds(2), new ObjectMapper());
    }

    @Test
    void uploadSendsBase64ContentAndReadsTheDiff() throws IOException {
        reply.set("{\"ok\":true,\"data\":{\"doc_id\":\"POL-SALES\",\"version\":\"v1.2\",\"status\":\"현행\","
                + "\"title\":\"영업 업무 내규\",\"chunks\":48,\"clauses\":21,\"retired_docs\":[\"POL-SALES@v1.1\"],"
                + "\"diff\":{\"base\":\"POL-SALES@v1.1\",\"added\":[\"ART-20\"],\"removed\":[],\"changed\":[\"ART-04\"]},"
                + "\"duplicate\":false,\"rag_reloaded\":true}}");
        byte[] content = "제1조 목적\n제2조 정의".getBytes(StandardCharsets.UTF_8);

        PolicyDocumentSaved saved = client().upload(new PolicyDocumentGateway.PolicyUpload("C-1", "v1.2", "내규.txt",
                content, "영업 업무 내규", LocalDate.of(2026, 11, 1), "가상 팀장"));

        assertThat(saved).isEqualTo(new PolicyDocumentSaved("POL-SALES", "v1.2", "영업 업무 내규", "현행", 48, 21,
                List.of("POL-SALES@v1.1"), new PolicyDocumentSaved.Diff("POL-SALES@v1.1", List.of("ART-20"), List.of(),
                List.of("ART-04")), false, true));
        assertThat(seenPath.get()).isEqualTo("/internal/documents");
        assertThat(seenKey.get()).isEqualTo("k-123");
        JsonNode b = json.readTree(seenBody.get());
        assertThat(b.path("company_id").asText()).isEqualTo("C-1");
        assertThat(b.path("version").asText()).isEqualTo("v1.2");
        assertThat(b.path("source_file").asText()).isEqualTo("내규.txt");
        assertThat(Base64.getDecoder().decode(b.path("content_base64").asText())).isEqualTo(content);
        assertThat(b.path("title").asText()).isEqualTo("영업 업무 내규");
        assertThat(b.path("effective_from").asText()).isEqualTo("2026-11-01");
        assertThat(b.path("uploaded_by").asText()).isEqualTo("가상 팀장");
    }

    @Test
    void optionalFieldsAreOmittedAndFirstPolicyHasNoDiff() throws IOException {
        reply.set("{\"ok\":true,\"data\":{\"doc_id\":\"POL-SALES\",\"version\":\"v1.0\",\"status\":\"현행\","
                + "\"title\":\"영업 업무 내규\",\"chunks\":3,\"clauses\":3,\"retired_docs\":[],\"diff\":null,"
                + "\"duplicate\":false,\"rag_reloaded\":false}}");

        PolicyDocumentSaved saved = client().upload(new PolicyDocumentGateway.PolicyUpload("C-1", "v1.0", "내규.pdf",
                new byte[]{1, 2}, null, null, null));

        assertThat(saved.diff()).isNull();
        assertThat(saved.ragReloaded()).isFalse();
        JsonNode b = json.readTree(seenBody.get());
        assertThat(b.has("title")).isFalse();
        assertThat(b.has("effective_from")).isFalse();
        assertThat(b.has("uploaded_by")).isFalse();
    }

    @Test
    void errorEnvelopeBecomesRejectedAndUnreachableServerIsServiceUnavailable() {
        status.set(400);
        reply.set("{\"ok\":false,\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"조항(제N조·별표 N.)을 찾지 못했습니다\"}}");
        var upload = new PolicyDocumentGateway.PolicyUpload("C-1", "v1.2", "내규.pdf", new byte[]{1}, null, null, null);

        assertThatThrownBy(() -> client().upload(upload))
                .isInstanceOf(RubricGateway.Rejected.class)
                .hasMessageContaining("조항")
                .extracting(e -> ((RubricGateway.Rejected) e).code()).isEqualTo("VALIDATION_ERROR");

        McpPolicyDocumentClient unreachable = new McpPolicyDocumentClient(RestClient.builder(),
                new McpProperties("http://127.0.0.1:1", "k", Duration.ofSeconds(1)), Duration.ofSeconds(1),
                new ObjectMapper());
        assertThatThrownBy(() -> unreachable.upload(upload))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);
    }
}
