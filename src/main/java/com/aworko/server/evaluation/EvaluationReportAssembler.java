package com.aworko.server.evaluation;

import com.aworko.server.evaluation.dto.EvaluationReport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 평가 초안 items와 코칭 items를 화면용 리포트로 합친다.
 * 미관측은 0점이 아니다: 점수 합·만점에서 빼고 따로 센다 (기획안 9·10장).
 */
public final class EvaluationReportAssembler {

    static final String SCORED = "채점";
    static final String UNOBSERVED = "미관측";
    static final String NEEDS_REVIEW = "검토 필요";

    private EvaluationReportAssembler() {
    }

    public static EvaluationReport assemble(JsonNode draftItems, JsonNode coachingItems) {
        if (draftItems == null || !draftItems.isArray() || draftItems.isEmpty()) {
            return EvaluationReport.empty();
        }
        Map<String, List<EvaluationReport.Coaching>> coachingByItem = groupCoaching(coachingItems);

        List<EvaluationReport.Item> items = new ArrayList<>();
        int total = 0, scored = 0, unobserved = 0, needsReview = 0;
        for (JsonNode d : draftItems) {
            String itemId = d.path("item_id").asText(null);
            String status = d.path("status").asText(null);
            Integer score = SCORED.equals(status) && d.path("score").isNumber() ? d.path("score").asInt() : null;
            if (score != null) {
                total += score;
                scored++;
            } else if (UNOBSERVED.equals(status)) {
                unobserved++;
            } else if (NEEDS_REVIEW.equals(status)) {
                needsReview++;
            }
            items.add(new EvaluationReport.Item(itemId, d.path("item").asText(null), status, score,
                    scoreLabel(status, score), d.path("reason").asText(null), texts(d.path("evidence_ids")),
                    coachingByItem.getOrDefault(itemId, List.of())));
        }
        int observedRate = Math.round(scored * 100f / items.size());
        return new EvaluationReport(total, scored * 2, scored, unobserved, needsReview, observedRate, items);
    }

    static String scoreLabel(String status, Integer score) {
        if (score != null) {
            return score + "점";
        }
        return status == null ? "-" : status;
    }

    private static Map<String, List<EvaluationReport.Coaching>> groupCoaching(JsonNode coachingItems) {
        Map<String, List<EvaluationReport.Coaching>> map = new LinkedHashMap<>();
        if (coachingItems == null || !coachingItems.isArray()) {
            return map;
        }
        for (JsonNode c : coachingItems) {
            map.computeIfAbsent(c.path("item_id").asText(""), k -> new ArrayList<>())
                    .add(new EvaluationReport.Coaching(c.path("kind").asText(null), c.path("text").asText(null),
                            c.hasNonNull("target_span_id") ? c.get("target_span_id").asText() : null));
        }
        return map;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
