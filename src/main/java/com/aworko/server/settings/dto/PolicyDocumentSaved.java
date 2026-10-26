package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 내규 적재 결과(business-mcp /internal/documents 응답) */
public record PolicyDocumentSaved(
        @Schema(example = "POL-SALES") String docId,
        @Schema(example = "v1.2") String version,
        @Schema(example = "영업 업무 내규") String title,
        @Schema(description = "현행", example = "현행") String status,
        @Schema(description = "조·항 구간 수", example = "48") int chunks,
        @Schema(description = "조항 수", example = "21") int clauses,
        @Schema(description = "이번 업로드로 폐기된 이전 현행({doc_id}@{version})") List<String> retiredDocs,
        @Schema(description = "직전 현행과 구간 대조. 첫 내규면 null") Diff diff,
        @Schema(description = "같은 버전·같은 내용을 다시 올림(아무것도 바뀌지 않음)") boolean duplicate,
        @Schema(description = "rag-mcp 검색 사본 갱신 여부. false면 기준 생성이 이전 버전을 볼 수 있다") boolean ragReloaded) {

    @Schema(name = "PolicyDocumentDiff")
    public record Diff(
            @Schema(example = "POL-SALES@v1.1") String base,
            @Schema(description = "추가된 구간 ID") List<String> added,
            @Schema(description = "삭제된 구간 ID") List<String> removed,
            @Schema(description = "내용이 바뀐 구간 ID") List<String> changed) {
    }
}
