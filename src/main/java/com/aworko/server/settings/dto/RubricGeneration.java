package com.aworko.server.settings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 평가 기준 초안 자동 생성 한 건. 대기 → 완료(초안 저장됨) | 실패 */
public record RubricGeneration(
        @Schema(example = "RG-3f2a9c1e") String generationId,
        @Schema(description = "대기 | 완료 | 실패", example = "대기") String status,
        @Schema(description = "생성 입력으로 고정한 내규", example = "POL-SALES") String policyDocId,
        @Schema(example = "v1.2") String policyVersion,
        @Schema(example = "영업업무내규_v1.2.pdf") String sourceFile,
        @Schema(description = "만들 업무 유형", example = "[\"rfp_analysis\",\"proposal\",\"quote\",\"inquiry_reply\"]")
        List<String> workTypes,
        @Schema(description = "완료: 저장된 초안. 승인 전까지 평가에 쓰이지 않는다") List<Draft> drafts,
        @Schema(description = "실패: Worker 오류 코드(TIMED_OUT은 응답 없이 시간 초과)", example = "POLICY_INCOMPLETE")
        String error,
        @Schema(description = "실패: 화면에 보일 설명") String errorMessage,
        @Schema(example = "L-3001") String requestedBy,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {

    @Schema(name = "RubricGenerationDraft")
    public record Draft(@Schema(example = "quote") String workType, @Schema(example = "RUB-quote") String docId,
                        @Schema(example = "v3") String version) {
    }
}
