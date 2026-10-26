package com.aworko.server.aggregation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record TeamEvaluationSummary(
        @Schema(description = "팀 평가 요청 ID(UUID)") String requestId,
        @Schema(description = "대기 | 완료 | 실패", example = "대기") String status,
        @Schema(description = "실패 사유(Worker 오류 코드). 대기가 너무 오래면 TIMED_OUT", nullable = true) String error,
        @Schema(example = "2026-10-19") LocalDate periodFrom,
        @Schema(description = "끝 날짜 포함", example = "2026-10-25") LocalDate periodTo,
        @Schema(description = "공개 승인. 기간에 확정 평가가 있는 모든 업무 유형을 집계 승인했을 때만 true. "
                + "false면 수치 없이 보류(withheld) 리포트가 만들어진다") boolean releaseApproved,
        @Schema(description = "리포트 결과: generated | insufficient_data | withheld. 완료 전이면 null", nullable = true)
        String reportStatus,
        String requestedBy,
        OffsetDateTime requestedAt,
        @Schema(nullable = true) OffsetDateTime completedAt,
        @Schema(description = "요청 응답에만: 집계 승인이 없어 공개 승인을 막은 업무 유형", nullable = true)
        List<String> unapprovedWorkTypes) {

    public TeamEvaluationSummary withUnapprovedWorkTypes(List<String> workTypes) {
        return new TeamEvaluationSummary(requestId, status, error, periodFrom, periodTo, releaseApproved, reportStatus,
                requestedBy, requestedAt, completedAt, workTypes);
    }
}
