package com.aworko.server.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aworko.server.global.config.McpProperties;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** business-mcp 내부 API 응답 형식({ok, data} / {ok:false, error})을 실제 HTTP로 주고받아 확인한다. */
class McpLiveRequestClientTest {

    HttpServer server;
    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final AtomicReference<String> seenKey = new AtomicReference<>();
    final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/live-evaluations", exchange -> {
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

    McpLiveRequestClient client() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new McpLiveRequestClient(RestClient.builder(), new McpProperties(url, "k-123", Duration.ofSeconds(2)),
                new ObjectMapper());
    }

    @Test
    void createdRequestReturnsFixedRangeAndSendsInternalKey() {
        reply.set("{\"ok\":true,\"data\":{\"live_eval_id\":\"LIVE-abc\",\"from_seq\":3,\"to_seq\":12,"
                + "\"rubric_version\":\"v2\",\"status\":\"대기\",\"created\":true}}");

        LiveRequestGateway.LiveRequest r = client().create("W-1", "E-1021");

        assertThat(r).isEqualTo(new LiveRequestGateway.LiveRequest("LIVE-abc", 3, 12, "v2"));
        assertThat(seenKey.get()).isEqualTo("k-123");
        assertThat(seenBody.get()).contains("\"work_id\":\"W-1\"").contains("\"requested_by\":\"E-1021\"");
    }

    @Test
    void nullRubricStaysNull() {
        reply.set("{\"ok\":true,\"data\":{\"live_eval_id\":\"LIVE-x\",\"from_seq\":1,\"to_seq\":2,\"rubric_version\":null}}");

        assertThat(client().create("W-1", "E-1021").rubricVersion()).isNull();
    }

    @Test
    void errorEnvelopeBecomesRejectedWithMcpCode() {
        status.set(400);
        reply.set("{\"ok\":false,\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"완료된 질문·응답이 아직 없어\"}}");

        assertThatThrownBy(() -> client().create("W-1", "E-1021"))
                .isInstanceOf(LiveRequestGateway.Rejected.class)
                .hasMessage("완료된 질문·응답이 아직 없어")
                .extracting(e -> ((LiveRequestGateway.Rejected) e).code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void nonJsonOrUnreachableServerIsServiceUnavailable() {
        status.set(502);
        reply.set("<html>Bad Gateway</html>");
        assertThatThrownBy(() -> client().create("W-1", "E-1021"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);

        McpLiveRequestClient unreachable = new McpLiveRequestClient(RestClient.builder(),
                new McpProperties("http://127.0.0.1:1", "k", Duration.ofSeconds(1)), new ObjectMapper());
        assertThatThrownBy(() -> unreachable.create("W-1", "E-1021"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);
    }
}
