package com.aworko.server.runtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aworko.server.aggregation.TeamEvaluationProperties;
import com.aworko.server.aggregation.TeamEvaluationRows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 리포트 전·실패 없음·staleAfter 안의 팀 평가 요청마다 /internal/team-runs 토큰을 Worker 파일 형식으로 둔다 */
class TeamTokenSupplierTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneId.of("Asia/Seoul"));
    static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    @TempDir Path dir;
    final ObjectMapper objectMapper = new ObjectMapper();
    final TeamEvaluationRows rows = mock(TeamEvaluationRows.class);

    @Test
    void pendingTeamRequestsGetTokensAndFinishedOnesAreRemoved() throws IOException {
        Path file = dir.resolve("team-tokens.json");
        String first = "0b7d8a3e-6f1c-4c9a-9f5e-2a1b3c4d5e6f";
        String second = "1c8e9b4f-7a2d-4dab-8a6f-3b2c4d5e6f70";
        // 30분 안에 들어온 대기 요청만 묻는다
        when(rows.pendingRequestIds(NOW.minusMinutes(30))).thenReturn(List.of(first, second), List.of(second));
        List<String> issued = new ArrayList<>();
        TeamRunTokenGateway gateway = (id, ttl) -> {
            issued.add(id);
            return new RunTokenGateway.IssuedToken("team-" + id, NOW.plus(ttl));
        };
        TeamTokenSupplier supplier = new TeamTokenSupplier(rows, gateway, new TeamTokenProperties(file.toString(),
                Duration.ofHours(2), Duration.ofMinutes(30), Duration.ofSeconds(3)),
                new TeamEvaluationProperties(Duration.ofMinutes(30)), objectMapper, CLOCK);

        assertThat(supplier.refresh()).isEqualTo(new RunTokenFileRefresher.Result(2, 2, 0, true));
        JsonNode tokens = objectMapper.readTree(file.toFile()).path("tokens");
        assertThat(tokens.path(first).path("token").asText()).isEqualTo("team-" + first);
        assertThat(tokens.path(first).path("expires_at").asText()).isEqualTo("2026-10-23T19:00:00+09:00");

        // 첫 요청이 완료(또는 실패)되면 다음 주기에 빠지고, 남은 요청은 다시 발급하지 않는다
        assertThat(supplier.refresh()).isEqualTo(new RunTokenFileRefresher.Result(1, 0, 1, true));
        assertThat(issued).containsExactly(first, second);
        assertThat(objectMapper.readTree(file.toFile()).path("tokens").has(first)).isFalse();
    }
}
