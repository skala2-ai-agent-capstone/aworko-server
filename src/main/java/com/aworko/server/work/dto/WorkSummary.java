package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record WorkSummary(
        @Schema(example = "W-3F2A9C1D0B4E") String workId,
        @Schema(example = "카카오엔터프라이즈향 RFP 분석") String title,
        @Schema(example = "rfp_analysis") String workType,
        @Schema(example = "RFP 요구사항 분석") String workTypeLabel,
        @Schema(description = "프론트 유형 키 rfp | proposal | quote | reply", example = "rfp") String typeKey,
        @Schema(description = "고객 미지정이면 생략", example = "한빛제약(가상)") String customerName,
        @Schema(example = "E-1021") String employeeId,
        @Schema(example = "가상 직원 A") String employeeName,
        @Schema(description = "work_cases.status (in_progress | completed)", example = "in_progress") String status,
        @Schema(description = "화면 표시 단계: 작성 중 | 평가 대기 | 평가 중 | 확인 대기 | 검토 중 | 확정 | 자료 보류 | 평가 실패",
                example = "작성 중") String stage,
        @Schema(description = "최근 평가 건") String evaluationId,
        @Schema(description = "최근 평가 상태", example = "확인 대기") String evaluationStatus,
        @Schema(description = "최근 산출물 버전", example = "v2") String artifactVersion,
        @Schema(description = "생성·대화·산출물 저장·평가 중 가장 최근 시각") OffsetDateTime updatedAt) {
}
