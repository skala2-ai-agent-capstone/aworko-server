package com.aworko.server.work;

import com.aworko.server.work.dto.LiveFeedbackDisplay;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Agent가 저장한 결과(items: kind·diagnosis·criterion_id, coaching.suggestions)를 패널 표시 모양으로 바꾼다.
 * 항목 이름은 요청 때 고정한 평가 기준(rubric_items)에서 찾는다.
 */
@Component
@RequiredArgsConstructor
public class LiveFeedbackDisplays {

    private static final Map<String, String> STATE = Map.of("strength", "good", "improvement", "warn", "needs_check", "pending");
    private static final Map<String, String> KIND_LABEL = Map.of("strength", "잘한 점", "improvement", "보완할 점",
            "needs_check", "확인 필요");

    private final JdbcTemplate jdbc;

    public LiveFeedbackDisplay of(LiveEvaluation live) {
        if (live.getStatus() != LiveEvaluationStatus.COMPLETED || live.getItems() == null || !live.getItems().isArray()) {
            return null;
        }
        Map<String, String> names = new HashMap<>();
        if (live.getRubricDocId() != null) {
            jdbc.query("SELECT item_id, item FROM rubric_items WHERE doc_id = ? AND version = ?",
                    rs -> {
                        names.put(rs.getString(1), rs.getString(2));
                    }, live.getRubricDocId(), live.getRubricVersion());
        }
        return of(live.getItems(), live.getCoaching(), names);
    }

    static LiveFeedbackDisplay of(JsonNode items, JsonNode coaching, Map<String, String> rubricNames) {
        List<LiveFeedbackDisplay.Item> out = new ArrayList<>();
        for (JsonNode item : items) {
            String kind = item.path("kind").asText();
            String criterion = item.path("criterion_id").asText(null);
            String label = criterion != null && rubricNames.containsKey(criterion)
                    ? rubricNames.get(criterion) : KIND_LABEL.getOrDefault(kind, "피드백");
            out.add(new LiveFeedbackDisplay.Item(label, STATE.getOrDefault(kind, "pending"),
                    item.path("diagnosis").asText("")));
        }
        JsonNode first = coaching == null ? null : coaching.path("suggestions").path(0);
        return new LiveFeedbackDisplay(out,
                first == null || first.isMissingNode() ? null : first.path("next_action").asText(null),
                first == null || first.isMissingNode() ? null : first.path("suggested_prompt").asText(null));
    }
}
