package com.aworko.server.global.common;

import java.util.Arrays;

/**
 * DB에 enum 이름이 아닌 코드 문자열(employee, rfp_analysis, 확인 대기 …)로 저장되는 값.
 * 기존 스키마의 CHECK 제약 값과 code가 정확히 일치해야 한다.
 */
public interface CodedEnum {

    String getCode();

    static <E extends Enum<E> & CodedEnum> E fromCode(Class<E> type, String code) {
        return Arrays.stream(type.getEnumConstants())
                .filter(e -> e.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown " + type.getSimpleName() + " code: " + code));
    }
}
