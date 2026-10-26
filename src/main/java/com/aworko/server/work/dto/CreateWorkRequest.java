package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateWorkRequest(
        @Schema(description = "업무명", example = "카카오엔터프라이즈향 RFP 분석") @NotBlank @Size(max = 200) String title,
        @Schema(description = "rfp_analysis | proposal | quote | inquiry_reply 또는 프론트 키 rfp | reply. "
                + "평가 기준이 유형별로 정해지므로 필수",
                example = "rfp", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String workType,
        @Schema(description = "고객 ID(GET /api/customers). 비우면 고객 미지정", example = "CU-HANBIT-PHARMA") String customerId,
        @Schema(description = "「목적·요구사항」 한 칸. registration.purpose로 저장된다", example = "목적: SI 제안 사전 분석")
        @Size(max = 4000) String purpose,
        @Schema(description = "업무 등록 내용. key는 GET /api/work-types의 registration_fields. "
                + "비우면 등록 기록 없음(미관측), 빈 문자열은 등록했지만 비어 있음")
        @Size(max = 20) Map<String, String> registration) {
}
