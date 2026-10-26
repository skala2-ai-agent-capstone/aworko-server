package com.aworko.server.work.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public record WorkDetail(
        WorkSummary summary,
        @Schema(description = "산출물 이름", example = "요구사항 대응표") String artifactTitle,
        @Schema(description = "고객 ID") String customerId,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        @Schema(description = "담당자 본인이면 true(대화·저장·완료 가능). 팀장 조회는 false") boolean editable,
        @Schema(description = "업무 등록 내용. 본인은 원문, 팀장은 마스킹본. 등록 기록이 없으면 생략") JsonNode registration,
        @Schema(description = "registration을 화면 이름과 함께 순서대로 펼친 것(목적·요구사항 → 유형별 항목). 등록 기록이 없으면 빈 목록")
        List<RegistrationItem> registrationItems,
        List<SourceDocumentResponse> sourceDocuments,
        List<InternalReference> internalReferences,
        @Schema(description = "최근 산출물. 아직 없으면 생략") ArtifactResponse artifact) {
}
