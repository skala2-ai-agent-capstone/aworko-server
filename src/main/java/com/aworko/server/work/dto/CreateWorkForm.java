package com.aworko.server.work.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/** 「새 업무 생성」 multipart 폼 칸(Swagger 문서용). 바인딩은 컨트롤러가 칸마다 받는다 */
public record CreateWorkForm(
        @Schema(description = "업무명 · 고객사", example = "카카오엔터프라이즈향 RFP 분석",
                requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(description = "rfp | proposal | quote | reply (또는 rfp_analysis | inquiry_reply). 평가 기준이 유형별로 정해지므로 필수",
                example = "rfp", requiredMode = Schema.RequiredMode.REQUIRED) String workType,
        @Schema(description = "목적 · 요구사항(최대 4000자). registration.purpose로 저장", example = "목적: SI 제안 사전 분석")
        String purpose,
        @Schema(description = "고객 ID(GET /api/customers). 비우면 고객 미지정") String customerId,
        @ArraySchema(arraySchema = @Schema(description = "고객 자료(여러 개, 파일당 20MB). PDF·DOCX·XLSX·PPTX·텍스트는 내용을 꺼낸다"))
        List<MultipartFile> files) {
}
