package com.aworko.server.work;

import static org.assertj.core.api.Assertions.assertThat;

import com.aworko.server.work.dto.LiveFeedbackDisplay;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveFeedbackDisplaysTest {

    final ObjectMapper om = new ObjectMapper();

    @Test
    void mapsKindsToPanelStatesAndCriteriaToRubricNames() throws Exception {
        var items = om.readTree("[{\"item_id\":\"f1\",\"criterion_id\":\"q.3\",\"kind\":\"improvement\",\"diagnosis\":\"대조 안 함\"},"
                + "{\"item_id\":\"f2\",\"kind\":\"strength\",\"diagnosis\":\"조건이 구체적\"},"
                + "{\"item_id\":\"f3\",\"criterion_id\":\"q.9\",\"kind\":\"needs_check\",\"diagnosis\":\"확인 필요\"}]");
        var coaching = om.readTree("{\"suggestions\":[{\"next_action\":\"가격표와 대조\",\"suggested_prompt\":\"v2 가격표로 다시 계산해줘\"}]}");

        LiveFeedbackDisplay d = LiveFeedbackDisplays.of(items, coaching, Map.of("q.3", "결과 검증"));

        assertThat(d.items()).extracting(LiveFeedbackDisplay.Item::label).containsExactly("결과 검증", "잘한 점", "확인 필요");
        assertThat(d.items()).extracting(LiveFeedbackDisplay.Item::state).containsExactly("warn", "good", "pending");
        assertThat(d.items().get(1).note()).isEqualTo("조건이 구체적");
        assertThat(d.coaching()).isEqualTo("가격표와 대조");
        assertThat(d.suggestedPrompt()).isEqualTo("v2 가격표로 다시 계산해줘");
        assertThat(LiveFeedbackDisplays.of(items, null, Map.of()).coaching()).isNull();
    }
}
