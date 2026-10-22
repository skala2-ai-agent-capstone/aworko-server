package com.aworko.server.evaluation.dto;

import com.aworko.server.evaluation.ProgressLog;
import com.aworko.server.evaluation.ReviewRequest;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 개인 평가 리포트: 평가 결과 → 원문 근거 → 개선 방법 (기획안 7.2). 진행 중이면 report는 비어 있다. */
public record EvaluationDetail(
        EvaluationSummary evaluation,
        @Schema(description = "평가 초안 차수. 아직 없으면 null", nullable = true) Integer draftRevision,
        EvaluationReport report,
        @Schema(description = "진행 기록 (수집 중 → 평가 중 → 근거 확인 중 → 코칭 생성 중)") List<Progress> progress,
        List<Review> reviewRequests) {

    public record Progress(String stage, OffsetDateTime at) {
        public static Progress of(ProgressLog p) {
            return new Progress(p.getStage(), p.getAt());
        }
    }

    public record Review(String reviewRequestId, JsonNode targetItems, String reason,
                         @Schema(description = "담당 팀장") String reviewerId, OffsetDateTime createdAt) {
        public static Review of(ReviewRequest r) {
            return new Review(r.getReviewRequestId(), r.getTargetItems(), r.getReason(), r.getReviewerId(),
                    r.getCreatedAt());
        }
    }
}
