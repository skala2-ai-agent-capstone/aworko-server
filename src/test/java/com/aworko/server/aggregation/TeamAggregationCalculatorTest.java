package com.aworko.server.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aworko.server.aggregation.TeamAggregationCalculator.Row;
import com.aworko.server.aggregation.dto.TeamDashboard;
import com.aworko.server.evaluation.EvaluationStatus;
import com.aworko.server.support.TestJson;
import com.aworko.server.work.WorkType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TeamAggregationCalculatorTest {

    static final LocalDate FROM = LocalDate.of(2026, 10, 19);
    static final LocalDate TO = LocalDate.of(2026, 10, 25);
    static final OffsetDateTime T = OffsetDateTime.parse("2026-10-20T10:00:00+09:00");

    @Test
    void usesOnlyLatestConfirmedEvaluationPerWork() {
        List<Row> rows = List.of(
                row("E1", "W1", "U1", WorkType.QUOTE, EvaluationStatus.CONFIRMED, T, draft(1)),
                row("E2", "W1", "U1", WorkType.QUOTE, EvaluationStatus.CONFIRMED, T.plusHours(1), draft(2)),
                row("E3", "W2", "U2", WorkType.QUOTE, EvaluationStatus.AWAITING_CONFIRMATION, T, draft(0)));

        List<Row> latest = TeamAggregationCalculator.latestConfirmedPerWork(rows);

        assertThat(latest).extracting(Row::evaluationId).containsExactly("E2");
    }

    @Test
    void restrictsTypeBelowMinimumMembersWithoutMergingOtherTypes() {
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(row("Q" + i, "WQ" + i, "U" + i, WorkType.QUOTE, EvaluationStatus.CONFIRMED, T, draft(i % 3)));
        }
        rows.add(row("P1", "WP1", "U1", WorkType.PROPOSAL, EvaluationStatus.CONFIRMED, T, draft(2)));

        TeamDashboard d = TeamAggregationCalculator.aggregate("T1", "영업1팀", FROM, TO, 5, rows, Map.of());

        TeamDashboard.WorkTypeSection quote = section(d, "quote");
        assertThat(quote.restricted()).isFalse();
        assertThat(quote.memberCount()).isEqualTo(5);
        assertThat(quote.items()).isNotEmpty();

        TeamDashboard.WorkTypeSection proposal = section(d, "proposal");
        assertThat(proposal.restricted()).isTrue();
        assertThat(proposal.items()).isEmpty();
        assertThat(proposal.topIssues()).isEmpty();
        assertThat(proposal.message()).contains("5명");
    }

    @Test
    void unobservedIsExcludedFromAverageAndDistribution() throws Exception {
        List<Row> rows = new ArrayList<>();
        rows.add(row("A", "W1", "U1", WorkType.QUOTE, EvaluationStatus.CONFIRMED, T, json(
                "[{\"item_id\":\"q.1\",\"item\":\"문제 정의\",\"status\":\"채점\",\"score\":2}]")));
        rows.add(row("B", "W2", "U2", WorkType.QUOTE, EvaluationStatus.CONFIRMED, T, json(
                "[{\"item_id\":\"q.1\",\"item\":\"문제 정의\",\"status\":\"채점\",\"score\":1}]")));
        rows.add(row("C", "W3", "U3", WorkType.QUOTE, EvaluationStatus.CONFIRMED, T, json(
                "[{\"item_id\":\"q.1\",\"item\":\"문제 정의\",\"status\":\"미관측\",\"score\":null}]")));

        List<TeamDashboard.ItemStat> stats = TeamAggregationCalculator.itemStats(rows);

        TeamDashboard.ItemStat q1 = stats.get(0);
        assertThat(q1.scoredCount()).isEqualTo(2);
        assertThat(q1.unobservedCount()).isEqualTo(1);
        assertThat(q1.averageScore()).isEqualTo(1.5);
        assertThat(q1.observedRate()).isEqualTo(67);
        assertThat(q1.distribution()).containsEntry("0", 0).containsEntry("1", 1).containsEntry("2", 1);
    }

    @Test
    void statsCountCollectedProcessedAndExcluded() {
        List<Row> rows = List.of(
                row("E1", "W1", "U1", WorkType.QUOTE, EvaluationStatus.CONFIRMED, T, draft(2)),
                row("E2", "W2", "U2", WorkType.QUOTE, EvaluationStatus.AWAITING_REVIEW, T, draft(1)),
                row("E3", "W3", "U3", WorkType.QUOTE, EvaluationStatus.EVALUATING, T.plusDays(2), null),
                row("E4", "W4", "U4", WorkType.RFP_ANALYSIS, EvaluationStatus.FAILED, T, null));

        TeamDashboard d = TeamAggregationCalculator.aggregate("T1", "영업1팀", FROM, TO, 5, rows, Map.of());

        assertThat(d.stats().collectedCount()).isEqualTo(4);
        assertThat(d.stats().processedCount()).isEqualTo(2);
        assertThat(d.stats().processRate()).isEqualTo(50);
        assertThat(d.stats().confirmedCount()).isEqualTo(1);
        assertThat(d.stats().excludedCount()).isEqualTo(3);
        assertThat(d.dailyTrend()).hasSize(7);
        assertThat(d.dailyTrend().get(1).count()).isEqualTo(3);   // 10-20
        assertThat(d.dailyTrend().get(3).count()).isEqualTo(1);   // 10-22
    }

    @Test
    void topIssuesCountWeaknessOncePerEvaluation() throws Exception {
        JsonNode coaching = json("""
                [{"item_id":"q.3","kind":"보완점","text":"a"},{"item_id":"q.3","kind":"보완점","text":"b"},
                 {"item_id":"q.1","kind":"강점","text":"c"}]""");
        List<Row> rows = List.of(
                new Row("A", "W1", "U1", WorkType.QUOTE, "v2", EvaluationStatus.CONFIRMED, T, draft(1), coaching),
                new Row("B", "W2", "U2", WorkType.QUOTE, "v2", EvaluationStatus.CONFIRMED, T, draft(1), coaching));

        List<TeamDashboard.Issue> issues = TeamAggregationCalculator.topIssues(rows);

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).itemId()).isEqualTo("q.3");
        assertThat(issues.get(0).label()).isEqualTo("결과 검증");
        assertThat(issues.get(0).count()).isEqualTo(2);
    }

    private static TeamDashboard.WorkTypeSection section(TeamDashboard d, String type) {
        return d.workTypes().stream().filter(s -> s.workType().equals(type)).findFirst().orElseThrow();
    }

    private static Row row(String evalId, String workId, String employee, WorkType type, EvaluationStatus status,
                           OffsetDateTime at, JsonNode draft) {
        return new Row(evalId, workId, employee, type, "v2", status, at, draft, null);
    }

    private static JsonNode draft(int score) {
        try {
            return json("[{\"item_id\":\"q.1\",\"item\":\"문제 정의\",\"status\":\"채점\",\"score\":" + score + "},"
                    + "{\"item_id\":\"q.3\",\"item\":\"결과 검증\",\"status\":\"미관측\",\"score\":null}]");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(String s) throws Exception {
        return TestJson.mapper().readTree(s);
    }
}
