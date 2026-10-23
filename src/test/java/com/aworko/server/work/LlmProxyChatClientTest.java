package com.aworko.server.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aworko.server.global.config.LlmProperties;
import com.aworko.server.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
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

/** llm-proxy와 실제 HTTP로 주고받아 추적 헤더·요청 본문·오류 처리를 확인한다. */
class LlmProxyChatClientTest {

    final ObjectMapper objectMapper = new ObjectMapper();
    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final AtomicReference<Headers> seenHeaders = new AtomicReference<>();
    final AtomicReference<JsonNode> seenBody = new AtomicReference<>();
    HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            seenHeaders.set(exchange.getRequestHeaders());
            seenBody.set(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
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

    LlmProxyChatClient client() {
        return new LlmProxyChatClient(RestClient.builder(), new LlmProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "pk-1", "gemma4", Duration.ofSeconds(2)),
                objectMapper);
    }

    @Test
    void sendsTraceHeadersAndConversationAndReturnsReply() {
        reply.set("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"정리했습니다\"},\"finish_reason\":\"stop\"}]}");

        String answer = client().reply("W-1", "E-1021", List.of(Map.of("role", "system", "content", "s"),
                Map.of("role", "user", "content", "질문")));

        assertThat(answer).isEqualTo("정리했습니다");
        Headers h = seenHeaders.get();
        assertThat(h.getFirst("Authorization")).isEqualTo("Bearer pk-1");
        assertThat(h.getFirst("X-Stage")).isEqualTo("workspace_chat");
        assertThat(h.getFirst("X-Work-Id")).isEqualTo("W-1");
        assertThat(h.getFirst("X-User-Id")).isEqualTo("E-1021");
        JsonNode body = seenBody.get();
        assertThat(body.path("model").asText()).isEqualTo("gemma4");
        assertThat(body.path("stream").asBoolean(true)).isFalse();
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("질문");
    }

    @Test
    void proxyErrorsAndMalformedRepliesAreServiceUnavailable() {
        status.set(400);
        reply.set("{\"error\":\"invalid_trace_headers\"}");
        assertThatThrownBy(() -> client().reply("W-1", "E-1", List.of(Map.of("role", "user", "content", "q"))))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);

        status.set(200);
        reply.set("{\"choices\":[]}");
        assertThatThrownBy(() -> client().reply("W-1", "E-1", List.of(Map.of("role", "user", "content", "q"))))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);

        LlmProxyChatClient unreachable = new LlmProxyChatClient(RestClient.builder(),
                new LlmProperties("http://127.0.0.1:1", "k", "m", Duration.ofSeconds(1)), objectMapper);
        assertThatThrownBy(() -> unreachable.reply("W-1", "E-1", List.of(Map.of("role", "user", "content", "q"))))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);
    }
}
