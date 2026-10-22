package com.aworko.server.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aworko.server.evaluation.dto.EvaluationReport;
import com.aworko.server.support.TestJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class EvaluationReportAssemblerTest {

    @Test
    void unobservedIsNotZeroAndIsExcludedFromTotalAndMax() throws Exception {
        JsonNode draft = json("""
                [{"item_id":"q.1","item":"문제 정의","status":"채점","score":2,"reason":"r1","evidence_ids":["W:M-1"]},
                 {"item_id":"q.2","item":"지시 품질","status":"채점","score":1,"reason":"r2","evidence_ids":[]},
                 {"item_id":"q.3","item":"결과 검증","status":"미관측","score":null,"reason":"기록 없음"},
                 {"item_id":"q.4","item":"보안 준수","status":"채점","score":2},
                 {"item_id":"q.5","item":"결과물 품질","status":"검토 필요","score":null}]
                """);
        JsonNode coaching = json("""
                [{"item_id":"q.2","kind":"보완점","text":"조건을 함께 전달하세요","target_span_id":"A:v2:L03"},
                 {"item_id":"q.2","kind":"강점","text":"자료를 첨부했습니다"},
                 {"item_id":"q.1","kind":"강점","text":"목적이 명확합니다"}]
                """);

        EvaluationReport r = EvaluationReportAssembler.assemble(draft, coaching);

        assertThat(r.totalScore()).isEqualTo(5);
        assertThat(r.maxScore()).isEqualTo(6);
        assertThat(r.scoredCount()).isEqualTo(3);
        assertThat(r.unobservedCount()).isEqualTo(1);
        assertThat(r.needsReviewCount()).isEqualTo(1);
        assertThat(r.observedRate()).isEqualTo(60);
        assertThat(r.items()).extracting(EvaluationReport.Item::scoreLabel)
                .containsExactly("2점", "1점", "미관측", "2점", "검토 필요");
        assertThat(r.items().get(2).score()).isNull();
        assertThat(r.items().get(0).evidenceIds()).containsExactly("W:M-1");
        assertThat(r.items().get(1).coaching()).hasSize(2);
        assertThat(r.items().get(1).coaching().get(0).targetSpanId()).isEqualTo("A:v2:L03");
        assertThat(r.items().get(4).coaching()).isEmpty();
    }

    @Test
    void noDraftYetGivesEmptyReport() {
        EvaluationReport r = EvaluationReportAssembler.assemble(null, null);
        assertThat(r.items()).isEmpty();
        assertThat(r.maxScore()).isZero();
        assertThat(r.observedRate()).isZero();
    }

    private static JsonNode json(String s) throws Exception {
        return TestJson.mapper().readTree(s);
    }
}
