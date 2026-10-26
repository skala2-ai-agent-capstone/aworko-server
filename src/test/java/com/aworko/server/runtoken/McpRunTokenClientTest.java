package com.aworko.server.runtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aworko.server.global.config.McpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** business-mcp /internal/runs 응답 형식({ok, data} / {ok:false, error})을 실제 HTTP로 주고받아 확인한다. */
class McpRunTokenClientTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneId.of("Asia/Seoul"));

    HttpServer server;
    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final AtomicReference<String> seenPath = new AtomicReference<>();
    final AtomicReference<String> seenKey = new AtomicReference<>();
    final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/", exchange -> {
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

    McpRunTokenClient client(String url) {
        return new McpRunTokenClient(RestClient.builder(), new McpProperties(url, "k-123", Duration.ofSeconds(2)),
                new ObjectMapper(), CLOCK);
    }

    McpRunTokenClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void issuedTokenExpiresAfterTtlFromRequestTimeAndSendsInternalKey() {
        reply.set("{\"ok\":true,\"data\":{\"run_token\":\"eyJr.sig\",\"run_id\":\"RUN-1\",\"evaluation_id\":\"EVAL-1\"}}");

        RunTokenGateway.IssuedToken token = client().issue("EVAL-1", Duration.ofHours(2));

        assertThat(token.token()).isEqualTo("eyJr.sig");
        assertThat(token.expiresAt()).isEqualTo(OffsetDateTime.now(CLOCK).plusHours(2));
        assertThat(seenPath.get()).isEqualTo("/internal/runs");
        assertThat(seenKey.get()).isEqualTo("k-123");
        assertThat(seenBody.get()).contains("\"evaluation_id\":\"EVAL-1\"").contains("\"ttl_sec\":7200");
    }

    @Test
    void teamTokenIsIssuedPerRequestFromTeamRuns() {
        reply.set("{\"ok\":true,\"data\":{\"run_token\":\"team.sig\",\"request_id\":\"0b7d8a3e-6f1c-4c9a-9f5e-2a1b3c4d5e6f\"}}");

        RunTokenGateway.IssuedToken token = client().issueTeam("0b7d8a3e-6f1c-4c9a-9f5e-2a1b3c4d5e6f",
                Duration.ofMinutes(30));

        assertThat(token.token()).isEqualTo("team.sig");
        assertThat(token.expiresAt()).isEqualTo(OffsetDateTime.now(CLOCK).plusMinutes(30));
        assertThat(seenPath.get()).isEqualTo("/internal/team-runs");
        assertThat(seenBody.get()).contains("\"request_id\":\"0b7d8a3e-6f1c-4c9a-9f5e-2a1b3c4d5e6f\"")
                .contains("\"ttl_sec\":1800");
    }

    @Test
    void errorEnvelopeBecomesRejectedWithMcpCode() {
        status.set(404);
        reply.set("{\"ok\":false,\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"평가 건 없음\"}}");

        assertThatThrownBy(() -> client().issue("EVAL-X", Duration.ofHours(1)))
                .isInstanceOf(RunTokenGateway.Rejected.class)
                .extracting(e -> ((RunTokenGateway.Rejected) e).code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void nonJsonMissingTokenOrUnreachableServerIsUnavailable() {
        status.set(500);
        reply.set("Internal Server Error");
        assertThatThrownBy(() -> client().issue("EVAL-1", Duration.ofHours(1)))
                .isInstanceOf(RunTokenGateway.Unavailable.class);

        status.set(200);
        reply.set("{\"ok\":true,\"data\":{}}");
        assertThatThrownBy(() -> client().issue("EVAL-1", Duration.ofHours(1)))
                .isInstanceOf(RunTokenGateway.Unavailable.class);

        assertThatThrownBy(() -> client("http://127.0.0.1:1").issue("EVAL-1", Duration.ofHours(1)))
                .isInstanceOf(RunTokenGateway.Unavailable.class);
    }
}
