package com.aworko.server.work;

import com.aworko.server.work.dto.WorkTypeInfo;
import com.aworko.server.work.dto.WorkTypeInfo.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 업무 유형별 산출물 이름과 등록 항목. 항목 key는 시드 업무의 work_cases.registration과 같아서
 * 평가(문제 정의 — 필수 기재 사항)가 그대로 읽는다.
 */
public final class WorkTypeCatalog {

    private static final Map<WorkType, WorkTypeInfo> INFO = Map.of(
            WorkType.RFP_ANALYSIS, info(WorkType.RFP_ANALYSIS, "요구사항 대응표",
                    f("rfp_document", "RFP 문서명·버전"), f("submission_deadline", "제출 기한"),
                    f("evaluation_method", "평가 방식"), f("customer_purpose", "고객 목적"),
                    f("required_requirements", "필수 요구사항")),
            WorkType.PROPOSAL, info(WorkType.PROPOSAL, "최종 제안서",
                    f("customer_purpose", "고객 목적"), f("scale", "규모(사용자 수 등)"),
                    f("required_requirements", "필수 요구사항"), f("schedule", "일정"),
                    f("constraints", "제약 조건"), f("rfp", "관련 RFP")),
            WorkType.QUOTE, info(WorkType.QUOTE, "견적서",
                    f("product", "상품"), f("quantity", "수량"), f("contract_months", "계약 기간(개월)"),
                    f("request_conditions", "요청 조건(할인·결제 등)"), f("budget", "예산")),
            WorkType.INQUIRY_REPLY, info(WorkType.INQUIRY_REPLY, "회신 메일",
                    f("question_original", "질문 원문"), f("question_items", "질문 항목"),
                    f("related_rfp", "관련 RFP·견적")));

    private WorkTypeCatalog() {
    }

    public static List<WorkTypeInfo> all() {
        return Arrays.stream(WorkType.values()).map(INFO::get).toList();
    }

    public static String artifactTitle(WorkType type) {
        return type == null ? "산출물" : INFO.get(type).artifactTitle();
    }

    /** 프론트 목업의 유형 키(rfp·proposal·quote·reply) */
    public static String typeKey(WorkType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case RFP_ANALYSIS -> "rfp";
            case PROPOSAL -> "proposal";
            case QUOTE -> "quote";
            case INQUIRY_REPLY -> "reply";
        };
    }

    /** 유형 코드(rfp_analysis …) 또는 프론트 키(rfp·reply …). 비어 있으면 null */
    public static WorkType parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (WorkType type : WorkType.values()) {
            if (type.getCode().equals(value) || typeKey(type).equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException(value);
    }

    /** 등록 항목 key → 화면 이름 */
    public static Map<String, String> registrationLabels(WorkType type) {
        return type == null ? Map.of() : INFO.get(type).registrationFields().stream()
                .collect(Collectors.toMap(Field::key, Field::label));
    }

    private static WorkTypeInfo info(WorkType type, String artifactTitle, Field... fields) {
        return new WorkTypeInfo(type.getCode(), type.getLabel(), artifactTitle, List.of(fields));
    }

    private static Field f(String key, String label) {
        return new Field(key, label);
    }
}
