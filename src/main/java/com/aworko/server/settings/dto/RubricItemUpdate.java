package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 초안 항목 하나에서 고칠 칸만 보낸다. 보내지 않은(null) 칸은 그대로 둔다 */
public record RubricItemUpdate(
        @Schema(description = "평가 질문") @Size(max = 2000) String criterion,
        @Schema(description = "2점 기준") @Size(max = 2000) String score2,
        @Schema(description = "1점 기준. 보안 준수는 '해당 없음'만") @Size(max = 2000) String score1,
        @Schema(description = "0점 기준") @Size(max = 2000) String score0,
        @Schema(description = "미관측 조건") @Size(max = 2000) String unobserved,
        @Schema(description = "검토 필요 조건") @Size(max = 2000) String needsReview,
        @Schema(description = "평가 때 볼 자료(1개 이상)", example = "[\"업무 등록 내용\", \"RFP 원문\"]")
        @Size(max = 20) List<String> evidenceSources,
        @Schema(description = "현행 내규 조항(1개 이상, GET /api/settings/policy-clauses)", example = "[\"ART-11\"]")
        @Size(max = 20) List<String> relatedClauses) {

    /** business-mcp /internal/rubrics/drafts/items의 fields(MCP 칸 이름) */
    public Map<String, Object> fields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        put(fields, "criterion", criterion);
        put(fields, "score_2", score2);
        put(fields, "score_1", score1);
        put(fields, "score_0", score0);
        put(fields, "unobserved", unobserved);
        put(fields, "needs_review", needsReview);
        put(fields, "evidence_sources", evidenceSources);
        put(fields, "related_clauses", relatedClauses);
        return fields;
    }

    private static void put(Map<String, Object> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value);
        }
    }
}
