package com.aworko.server.work;

import com.aworko.server.global.common.CodedEnum;
import com.aworko.server.global.common.CodedEnumConverter;
import jakarta.persistence.Converter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 영업 업무 4종 (work_cases.work_type, documents.work_type). */
@Getter
@RequiredArgsConstructor
public enum WorkType implements CodedEnum {

    RFP_ANALYSIS("rfp_analysis", "RFP 요구사항 분석"),
    PROPOSAL("proposal", "고객 맞춤 제안서 작성"),
    QUOTE("quote", "견적서 작성"),
    INQUIRY_REPLY("inquiry_reply", "고객 질의 답변 작성");

    private final String code;
    private final String label;

    @Converter(autoApply = true)
    public static class JpaConverter extends CodedEnumConverter<WorkType> {
        public JpaConverter() {
            super(WorkType.class);
        }
    }
}
