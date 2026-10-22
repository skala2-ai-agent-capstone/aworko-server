package com.aworko.server.evaluation;

import com.aworko.server.global.common.CodedEnum;
import com.aworko.server.global.common.CodedEnumConverter;
import jakarta.persistence.Converter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * evaluations.status. code는 001_schema.sql의 CHECK 값과 같아야 한다.
 * 진행 4단계는 Agent가 business-mcp로 기록하고, serverOnly 상태는 백엔드(서버 코드)만 기록한다.
 */
@Getter
@RequiredArgsConstructor
public enum EvaluationStatus implements CodedEnum {

    WAITING("대기", false),
    COLLECTING("수집 중", false),
    EVALUATING("평가 중", false),
    CHECKING_EVIDENCE("근거 확인 중", false),
    COACHING("코칭 생성 중", false),
    AWAITING_CONFIRMATION("확인 대기", true),
    AWAITING_REVIEW("검토 대기", true),
    CONFIRMED("확정", true),
    FAILED("처리 실패", true),
    ON_HOLD("자료 보류", true);

    private final String code;
    private final boolean serverOnly;

    /**
     * 화면 표시용 상태. 진행 4단계와 대기는 "평가 중"으로 묶는다.
     * (프론트 공통 코드: 작성 중·평가 중·확인 대기·확정·보류 + 검토 중·실패)
     */
    public String displayLabel() {
        return switch (this) {
            case WAITING, COLLECTING, EVALUATING, CHECKING_EVIDENCE, COACHING -> "평가 중";
            case AWAITING_CONFIRMATION -> "확인 대기";
            case AWAITING_REVIEW -> "검토 중";
            case CONFIRMED -> "확정";
            case ON_HOLD -> "보류";
            case FAILED -> "실패";
        };
    }

    @Converter(autoApply = true)
    public static class JpaConverter extends CodedEnumConverter<EvaluationStatus> {
        public JpaConverter() {
            super(EvaluationStatus.class);
        }
    }
}
