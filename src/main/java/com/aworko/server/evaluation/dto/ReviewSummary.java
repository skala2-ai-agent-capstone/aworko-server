package com.aworko.server.evaluation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 팀장에게 배정된 검토 건 목록의 한 행 */
public record ReviewSummary(
        @Schema(example = "RVQ-3f2a9c1d0b4e") String reviewRequestId,
        @Schema(example = "EVAL-E2E-QT-08-v2") String evaluationId,
        String taskId,
        @Schema(nullable = true) String taskTitle,
        @Schema(nullable = true) String workType,
        @Schema(description = "업무 유형 이름", nullable = true, example = "견적서 작성") String workTypeLabel,
        @Schema(description = "검토 대상 직원 ID") String employeeId,
        @Schema(description = "검토 대상 직원 이름", nullable = true, example = "가상 직원 A") String employeeName,
        @Schema(description = "검토 대상 항목") JsonNode targetItems,
        @Schema(description = "이의 제기 또는 채점 불일치 사유") String reason,
        @Schema(description = "평가 상태. 검토 대기면 처리할 건", example = "검토 대기") String status,
        String displayStatus,
        OffsetDateTime submittedAt) {
}
