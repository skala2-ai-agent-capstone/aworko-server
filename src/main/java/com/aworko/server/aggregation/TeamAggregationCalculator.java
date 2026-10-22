package com.aworko.server.aggregation;

import com.aworko.server.aggregation.dto.TeamDashboard;
import com.aworko.server.evaluation.EvaluationStatus;
import com.aworko.server.work.WorkType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 팀 집계 규칙 (기획안 10장). DB와 무관한 순수 계산이라 단위 테스트로 검증한다.
 * - 업무별 최신 확정 평가 1건만 사용
 * - 업무 유형별로만 집계, 유형별 확정 대상 인원이 최소 인원 미만이면 상세 통계 비공개(다른 유형과 합치지 않음)
 * - 미관측은 점수 평균·분포에서 제외하고 따로 센다
 */
public final class TeamAggregationCalculator {

    private static final Set<EvaluationStatus> PROCESSED = EnumSet.of(EvaluationStatus.AWAITING_CONFIRMATION,
            EvaluationStatus.AWAITING_REVIEW, EvaluationStatus.CONFIRMED);
    private static final String SCORED = "채점";
    private static final String UNOBSERVED = "미관측";
    private static final String WEAKNESS = "보완점";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private TeamAggregationCalculator() {
    }

    /** 집계 입력 한 행: 평가 건 + 업무 + 최신 초안·코칭 */
    public record Row(String evaluationId, String workId, String employeeId, WorkType workType,
                      String artifactVersion, EvaluationStatus status, OffsetDateTime createdAt,
                      JsonNode draftItems, JsonNode coachingItems) {
    }

    public static TeamDashboard aggregate(String teamId, String teamName, LocalDate from, LocalDate to,
                                          int minSize, List<Row> rows,
                                          Map<WorkType, TeamDashboard.Approval> approvals) {
        List<Row> confirmed = latestConfirmedPerWork(rows);

        int processed = (int) rows.stream().filter(r -> PROCESSED.contains(r.status())).count();
        int scoredTotal = 0, unobservedTotal = 0;
        for (Row r : confirmed) {
            for (JsonNode item : items(r.draftItems())) {
                String st = item.path("status").asText();
                if (SCORED.equals(st)) scoredTotal++;
                else if (UNOBSERVED.equals(st)) unobservedTotal++;
            }
        }
        Set<String> members = confirmed.stream().map(Row::employeeId).collect(Collectors.toSet());
        Set<WorkType> confirmedTypes = confirmed.stream().map(Row::workType).collect(Collectors.toSet());
        var stats = new TeamDashboard.Stats(rows.size(), processed, percent(processed, rows.size()), confirmed.size(),
                rows.size() - confirmed.size(), percent(unobservedTotal, scoredTotal + unobservedTotal),
                members.size(), confirmedTypes.size());

        List<TeamDashboard.WorkTypeSection> sections = new ArrayList<>();
        for (WorkType type : WorkType.values()) {
            List<Row> typeRows = rows.stream().filter(r -> r.workType() == type).toList();
            if (typeRows.isEmpty()) {
                continue;
            }
            List<Row> typeConfirmed = confirmed.stream().filter(r -> r.workType() == type).toList();
            sections.add(section(type, typeRows.size(), typeConfirmed, minSize, approvals.get(type)));
        }
        return new TeamDashboard(teamId, teamName, from, to, minSize, stats, dailyTrend(rows, from, to), sections);
    }

    static List<Row> latestConfirmedPerWork(List<Row> rows) {
        return rows.stream()
                .filter(r -> r.status() == EvaluationStatus.CONFIRMED)
                .collect(Collectors.toMap(Row::workId, Function.identity(),
                        (a, b) -> a.createdAt().isAfter(b.createdAt()) ? a : b, LinkedHashMap::new))
                .values().stream().toList();
    }

    static TeamDashboard.WorkTypeSection section(WorkType type, int collected, List<Row> confirmed, int minSize,
                                                 TeamDashboard.Approval approval) {
        int memberCount = (int) confirmed.stream().map(Row::employeeId).distinct().count();
        if (memberCount < minSize) {
            return new TeamDashboard.WorkTypeSection(type.getCode(), type.getLabel(), collected, confirmed.size(),
                    memberCount, true, "최소 집계 인원(" + minSize + "명) 미달로 상세 통계를 공개하지 않습니다",
                    List.of(), List.of(), approval);
        }
        return new TeamDashboard.WorkTypeSection(type.getCode(), type.getLabel(), collected, confirmed.size(),
                memberCount, false, null, itemStats(confirmed), topIssues(confirmed), approval);
    }

    static List<TeamDashboard.ItemStat> itemStats(List<Row> confirmed) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, int[]> dist = new LinkedHashMap<>();   // [0점, 1점, 2점]
        Map<String, Integer> unobserved = new LinkedHashMap<>();
        for (Row r : confirmed) {
            for (JsonNode item : items(r.draftItems())) {
                String id = item.path("item_id").asText();
                names.putIfAbsent(id, item.path("item").asText(id));
                dist.putIfAbsent(id, new int[3]);
                unobserved.putIfAbsent(id, 0);
                String st = item.path("status").asText();
                if (SCORED.equals(st) && item.path("score").isNumber()) {
                    int s = item.path("score").asInt();
                    if (s >= 0 && s <= 2) dist.get(id)[s]++;
                } else if (UNOBSERVED.equals(st)) {
                    unobserved.merge(id, 1, Integer::sum);
                }
            }
        }
        List<TeamDashboard.ItemStat> out = new ArrayList<>();
        for (String id : names.keySet()) {
            int[] d = dist.get(id);
            int scored = d[0] + d[1] + d[2];
            int unobs = unobserved.get(id);
            Double avg = scored == 0 ? null : Math.round((d[1] + 2.0 * d[2]) / scored * 100) / 100.0;
            Map<String, Integer> distribution = new TreeMap<>(Map.of("0", d[0], "1", d[1], "2", d[2]));
            out.add(new TeamDashboard.ItemStat(id, names.get(id), scored, unobs, percent(scored, scored + unobs), avg,
                    distribution));
        }
        return out;
    }

    static List<TeamDashboard.Issue> topIssues(List<Row> confirmed) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Row r : confirmed) {
            items(r.draftItems()).forEach(i -> names.putIfAbsent(i.path("item_id").asText(), i.path("item").asText()));
            Set<String> seenInThisEvaluation = new HashSet<>();
            for (JsonNode c : items(r.coachingItems())) {
                String id = c.path("item_id").asText();
                if (WEAKNESS.equals(c.path("kind").asText()) && seenInThisEvaluation.add(id)) {
                    counts.merge(id, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(3)
                .map(e -> new TeamDashboard.Issue(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()), e.getValue()))
                .toList();
    }

    static List<TeamDashboard.DailyCount> dailyTrend(List<Row> rows, LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> counts = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            counts.put(d, 0);
        }
        rows.forEach(r -> counts.computeIfPresent(r.createdAt().atZoneSameInstant(ZONE).toLocalDate(),
                (k, v) -> v + 1));
        return counts.entrySet().stream().map(e -> new TeamDashboard.DailyCount(e.getKey(), e.getValue())).toList();
    }

    private static List<JsonNode> items(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }

    private static int percent(int part, int whole) {
        return whole == 0 ? 0 : Math.round(part * 100f / whole);
    }
}
