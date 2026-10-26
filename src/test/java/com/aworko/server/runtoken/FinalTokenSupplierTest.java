package com.aworko.server.runtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aworko.server.evaluation.Evaluation;
import com.aworko.server.evaluation.EvaluationRepository;
import com.aworko.server.evaluation.EvaluationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 처리 중인 평가 건마다 Worker --token-file 형식으로 토큰을 두고, 필요할 때만 다시 발급하는지 */
class FinalTokenSupplierTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneId.of("Asia/Seoul"));
    static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    @TempDir Path dir;
    Path tokenFile;
    final ObjectMapper objectMapper = new ObjectMapper();
    final EvaluationRepository evaluations = mock(EvaluationRepository.class);
    final List<String> issuedFor = new ArrayList<>();
    RunTokenGateway gateway;

    @BeforeEach
    void setUp() {
        tokenFile = dir.resolve("run-tokens/final-tokens.json");
        gateway = (id, ttl) -> {
            issuedFor.add(id);
            return new RunTokenGateway.IssuedToken("token-" + id + "-" + issuedFor.size(), NOW.plus(ttl));
        };
    }

    FinalTokenSupplier supplier() {
        return new FinalTokenSupplier(evaluations, gateway, new FinalTokenProperties(tokenFile.toString(),
                Duration.ofHours(2), Duration.ofMinutes(30), Duration.ofSeconds(3)), objectMapper, CLOCK);
    }

    void inProgress(String... ids) {
        when(evaluations.findQueuedByStatusIn(any())).thenReturn(Arrays.stream(ids)
                .map(id -> Evaluation.create(id, "W-1", "C-1", "ART-1", "v2", "v2", id)).toList());
    }

    JsonNode tokens() throws IOException {
        return objectMapper.readTree(tokenFile.toFile()).path("tokens");
    }

    void existingFile(Map<String, FinalTokenFile.Entry> entries) throws IOException {
        new FinalTokenFile(tokenFile, objectMapper).write(entries);
    }

    @Test
    void writesWorkerFormatForInProgressEvaluationsWithOwnerOnlyPermission() throws IOException {
        inProgress("EVAL-A-v2", "EVAL-B-v1");

        RunTokenFileRefresher.Result result = supplier().refresh();

        assertThat(result).isEqualTo(new RunTokenFileRefresher.Result(2, 2, 0, true));
        assertThat(tokens().path("EVAL-A-v2").path("token").asText()).isEqualTo("token-EVAL-A-v2-1");
        assertThat(tokens().path("EVAL-A-v2").path("expires_at").asText()).isEqualTo("2026-10-23T19:00:00+09:00");
        assertThat(tokens().size()).isEqualTo(2);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile))).isEqualTo("rw-------");
        // 같은 디렉터리에 임시 파일이 남지 않는다
        try (var files = Files.list(tokenFile.getParent())) {
            assertThat(files).containsExactly(tokenFile);
        }
    }

    @Test
    void reissuesOnlyTokensCloseToExpiryAndSkipsRewriteWhenNothingChanged() throws IOException {
        existingFile(Map.of(
                "EVAL-FRESH", new FinalTokenFile.Entry("old-fresh", NOW.plusHours(1)),
                "EVAL-NEAR", new FinalTokenFile.Entry("old-near", NOW.plusMinutes(10))));
        inProgress("EVAL-FRESH", "EVAL-NEAR");

        RunTokenFileRefresher.Result first = supplier().refresh();

        assertThat(issuedFor).containsExactly("EVAL-NEAR");
        assertThat(first.issued()).isEqualTo(1);
        assertThat(tokens().path("EVAL-FRESH").path("token").asText()).isEqualTo("old-fresh");
        assertThat(tokens().path("EVAL-NEAR").path("token").asText()).isEqualTo("token-EVAL-NEAR-1");

        assertThat(supplier().refresh().written()).isFalse();
        assertThat(issuedFor).containsExactly("EVAL-NEAR");
    }

    @Test
    void finishedEvaluationsLeaveTheFileAndEmptyListIsStillWritten() throws IOException {
        existingFile(Map.of("EVAL-DONE", new FinalTokenFile.Entry("done", NOW.plusHours(1))));
        inProgress();

        RunTokenFileRefresher.Result result = supplier().refresh();

        assertThat(result).isEqualTo(new RunTokenFileRefresher.Result(0, 0, 1, true));
        assertThat(objectMapper.readTree(tokenFile.toFile()).toString()).isEqualTo("{\"tokens\":{}}");
    }

    @Test
    void unreachableMcpStopsIssuingButKeepsStillValidTokens() throws IOException {
        existingFile(Map.of("EVAL-NEAR", new FinalTokenFile.Entry("old-near", NOW.plusMinutes(10))));
        inProgress("EVAL-NEAR", "EVAL-NEW");
        List<String> attempts = new ArrayList<>();
        gateway = (id, ttl) -> {
            attempts.add(id);
            throw new RunTokenGateway.Unavailable("ConnectException");
        };

        RunTokenFileRefresher.Result result = supplier().refresh();

        assertThat(attempts).containsExactly("EVAL-NEAR");
        assertThat(result.issued()).isZero();
        assertThat(tokens().path("EVAL-NEAR").path("token").asText()).isEqualTo("old-near");
        assertThat(tokens().has("EVAL-NEW")).isFalse();
    }

    @Test
    void rejectedEvaluationIsSkippedForACooldownAndOthersStillGetTokens() throws IOException {
        inProgress("EVAL-GONE", "EVAL-OK");
        RunTokenGateway ok = gateway;
        List<String> rejected = new ArrayList<>();
        gateway = (id, ttl) -> {
            if (id.equals("EVAL-GONE")) {
                rejected.add(id);
                throw new RunTokenGateway.Rejected("NOT_FOUND", "평가 건 없음");
            }
            return ok.issue(id, ttl);
        };
        FinalTokenSupplier supplier = supplier();

        supplier.refresh();
        // 주기(3초)마다 다시 묻지 않는다. 같은 시각이면 아직 쉬는 중
        supplier.refresh();

        assertThat(rejected).containsExactly("EVAL-GONE");
        assertThat(tokens().has("EVAL-GONE")).isFalse();
        assertThat(tokens().has("EVAL-OK")).isTrue();
    }

    @Test
    void expiredTokensAndIdsTheWorkerWouldRejectAreLeftOut() throws IOException {
        existingFile(Map.of("EVAL-OLD", new FinalTokenFile.Entry("old", NOW.minusMinutes(1))));
        inProgress("EVAL-OLD", "EVAL BAD/ID");
        RunTokenGateway ok = gateway;
        gateway = (id, ttl) -> {
            if (id.equals("EVAL-OLD")) {
                throw new RunTokenGateway.Rejected("TEMPORARY_FAILURE", "잠시 후");
            }
            return ok.issue(id, ttl);
        };

        supplier().refresh();

        // 형식이 하나라도 틀리면 Worker가 파일 전체를 거부하므로 그런 항목은 넣지 않는다
        assertThat(tokens().size()).isZero();
        assertThat(FinalTokenFile.acceptable("EVAL BAD/ID", new FinalTokenFile.Entry("t", NOW))).isFalse();
        assertThat(FinalTokenFile.acceptable("EVAL-OK:1_v2", new FinalTokenFile.Entry("t", NOW))).isTrue();
    }

    @Test
    void brokenFileIsReplaced() throws IOException {
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, "{not json");
        inProgress("EVAL-A");

        supplier().refresh();

        assertThat(tokens().has("EVAL-A")).isTrue();
    }

    @Test
    void scansInProgressStatusesOnly() {
        inProgress();
        supplier().refresh();
        org.mockito.Mockito.verify(evaluations).findQueuedByStatusIn(EvaluationStatus.IN_PROGRESS);
        assertThat(EvaluationStatus.IN_PROGRESS).containsExactlyInAnyOrder(EvaluationStatus.WAITING,
                EvaluationStatus.COLLECTING, EvaluationStatus.EVALUATING, EvaluationStatus.CHECKING_EVIDENCE,
                EvaluationStatus.COACHING);
    }
}
