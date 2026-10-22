package com.aworko.server.work;

import com.aworko.server.global.common.CodedEnum;
import com.aworko.server.global.common.CodedEnumConverter;
import jakarta.persistence.Converter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** live_evaluations.status. 백엔드가 대기로 만들고, Agent가 MCP로 생성 중 → 완료/실패로 바꾼다. */
@Getter
@RequiredArgsConstructor
public enum LiveEvaluationStatus implements CodedEnum {

    WAITING("대기"),
    GENERATING("생성 중"),
    COMPLETED("완료"),
    FAILED("실패");

    private final String code;

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED;
    }

    @Converter(autoApply = true)
    public static class JpaConverter extends CodedEnumConverter<LiveEvaluationStatus> {
        public JpaConverter() {
            super(LiveEvaluationStatus.class);
        }
    }
}
