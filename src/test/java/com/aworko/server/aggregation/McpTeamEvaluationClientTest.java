package com.aworko.server.aggregation;

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
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** business-mcp /internal/team-evaluations 응답 형식({ok, data} / {ok:false, error})을 실제 HTTP로 주고받아 확인한다. */
class McpTeamEvaluationClientTest {

    static final String RID = "0b7d8a3e-6f1c-4c9a-9f5e-2a1b3c4d5e6f";

    HttpServer server;
    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final AtomicReference<String> seenKey = new AtomicReference<>();
    final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/team-evaluations", exchange -> {
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

    McpTeamEvaluationClient client(String url) {
        return new McpTeamEvaluationClient(RestClient.builder(), new McpProperties(url, "k-123", Duration.ofSeconds(2)),
                new ObjectMapper());
    }

    TeamEvaluationGateway.Created create(McpTeamEvaluationClient client) {
        return client.create("C-1", "T-1", LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-26"), true, RID,
                "L-3001");
    }

    @Test
    void createdRequestSendsScopeAndInternalKey() {
        reply.set("{\"ok\":true,\"data\":{\"request_id\":\"" + RID + "\",\"release_approved\":true,\"min_members\":5,"
                + "\"status\":\"대기\",\"created\":true}}");

        TeamEvaluationGateway.Created created = create(client("http://127.0.0.1:" + server.getAddress().getPort()));

        assertThat(created).isEqualTo(new TeamEvaluationGateway.Created(RID, true, 5, true));
        assertThat(seenKey.get()).isEqualTo("k-123");
        assertThat(seenBody.get()).contains("\"company_id\":\"C-1\"").contains("\"team_id\":\"T-1\"")
                .contains("\"period_start\":\"2026-10-19\"").contains("\"period_end\":\"2026-10-26\"")
                .contains("\"release_approved\":true").contains("\"request_id\":\"" + RID + "\"")
                .contains("\"requested_by\":\"L-3001\"");
    }

    @Test
    void errorEnvelopeBecomesRejectedAndUnreachableIsServiceUnavailable() {
        status.set(400);
        reply.set("{\"ok\":false,\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"기간은 최대 366일입니다\"}}");
        assertThatThrownBy(() -> create(client("http://127.0.0.1:" + server.getAddress().getPort())))
                .isInstanceOf(TeamEvaluationGateway.Rejected.class)
                .hasMessage("기간은 최대 366일입니다")
                .extracting(e -> ((TeamEvaluationGateway.Rejected) e).code()).isEqualTo("VALIDATION_ERROR");

        status.set(502);
        reply.set("<html>Bad Gateway</html>");
        assertThatThrownBy(() -> create(client("http://127.0.0.1:" + server.getAddress().getPort())))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);
        assertThatThrownBy(() -> create(client("http://127.0.0.1:1")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SERVICE_UNAVAILABLE);
    }
}
