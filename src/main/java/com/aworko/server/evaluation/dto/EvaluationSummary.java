package com.aworko.server.evaluation.dto;

import com.aworko.server.evaluation.Evaluation;
import com.aworko.server.work.WorkCase;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record EvaluationSummary(
        @Schema(example = "EVAL-E2E-QT-08-v2") String evaluationId,
        @Schema(description = "업무 ID", example = "W-E2E-QT-08") String taskId,
        @Schema(description = "업무 제목", nullable = true) String taskTitle,
        @Schema(description = "rfp_analysis | proposal | quote | inquiry_reply", nullable = true) String workType,
        @Schema(description = "업무 유형 이름", nullable = true, example = "견적서 작성") String workTypeName,
        @Schema(example = "v2") String artifactVersion,
        @Schema(example = "v2") String rubricVersion,
        @Schema(description = "DB 상태(10단계)", example = "확인 대기") String status,
        @Schema(description = "화면 표시 상태: 평가 중 | 확인 대기 | 검토 중 | 확정 | 보류 | 실패", example = "확인 대기")
        String displayStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static EvaluationSummary of(Evaluation e, WorkCase work) {
        return new EvaluationSummary(e.getEvaluationId(), e.getWorkId(),
                work == null ? null : work.getTitle(),
                work == null || work.getWorkType() == null ? null : work.getWorkType().getCode(),
                work == null || work.getWorkType() == null ? null : work.getWorkType().getLabel(),
                e.getArtifactVersion(), e.getRubricVersion(), e.getStatus().getCode(), e.getStatus().displayLabel(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
