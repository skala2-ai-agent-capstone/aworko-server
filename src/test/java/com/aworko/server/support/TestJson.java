package com.aworko.server.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** 단위 테스트용 ObjectMapper. 앱 설정(spring.jackson)과 같은 snake_case를 쓴다. */
public final class TestJson {

    private TestJson() {
    }

    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule());
    }
}
