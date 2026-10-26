package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 항목 저장 결과: 저장된 항목과 실제로 바뀐 칸(MCP 칸 이름) */
public record RubricItemSaved(
        RubricDetail.Item item,
        @Schema(example = "[\"score_2\", \"related_clauses\"]") List<String> changedFields) {
}
