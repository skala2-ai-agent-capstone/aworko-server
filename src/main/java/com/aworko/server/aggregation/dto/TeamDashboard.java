package com.aworko.server.aggregation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 팀 대시보드. 업무 유형별로만 집계한다(유형 간 합산·순위 없음). 개인 점수·대화는 내보내지 않는다.
 * 확정된 평가 중 업무별 최신 1건만 쓰고, 미관측은 0점으로 계산하지 않는다 (기획안 10장).
 */
public record TeamDashboard(
        String teamId,
        String teamName,
        LocalDate periodFrom,
        LocalDate periodTo,
        @Schema(description = "유형별 최소 집계 인원. 미달 유형은 상세 통계를 숨긴다", example = "5") int minAggregationSize,
        Stats stats,
        @Schema(description = "기간 내 일별 평가 접수 건수") List<DailyCount> dailyTrend,
        List<WorkTypeSection> workTypes) {

    public record Stats(
            @Schema(description = "수집 건수: 기간 내 접수된 평가 건") int collectedCount,
            @Schema(description = "평가 리포트까지 생성된 건(확인 대기·검토 대기·확정)") int processedCount,
            @Schema(description = "평가 처리율(%)") int processRate,
            @Schema(description = "확정 건수(업무별 최신 1건)") int confirmedCount,
            @Schema(description = "미확인·검토 중 등 집계 제외 건수") int excludedCount,
            @Schema(description = "확정 평가의 항목 중 미관측 비율(%)") int unobservedRate,
            @Schema(description = "확정 평가 대상 인원") int targetMemberCount,
            @Schema(description = "확정 평가가 있는 업무 유형 수") int targetTypeCount) {
    }

    public record DailyCount(LocalDate date, int count) {
    }

    public record WorkTypeSection(
            @Schema(example = "quote") String workType,
            @Schema(example = "견적서 작성") String workTypeName,
            int collectedCount,
            int confirmedCount,
            @Schema(description = "확정 평가 대상 인원") int memberCount,
            @Schema(description = "true면 최소 인원 미달로 상세 통계 비공개") boolean restricted,
            @Schema(nullable = true) String message,
            @Schema(description = "restricted면 빈 목록") List<ItemStat> items,
            @Schema(description = "반복 보완점 Top 3. restricted면 빈 목록") List<Issue> topIssues,
            @Schema(description = "이 기간·유형의 최근 승인. 없으면 null", nullable = true) Approval approval) {
    }

    public record ItemStat(
            String itemId,
            String name,
            int scoredCount,
            int unobservedCount,
            @Schema(description = "평가 가능 비율(%)") int observedRate,
            @Schema(description = "채점 항목 평균(0~2). 채점 0건이면 null", nullable = true) Double averageScore,
            @Schema(description = "점수 분포 {\"0\":n, \"1\":n, \"2\":n}") Map<String, Integer> distribution) {
    }

    public record Issue(String itemId, String label, int count) {
    }

    public record Approval(Long approvalId, String approvedBy, OffsetDateTime approvedAt) {
    }
}
