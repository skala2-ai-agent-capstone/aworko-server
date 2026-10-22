package com.aworko.server.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** 테스트에서 고정 시각으로 바꿀 수 있게 Clock을 주입받아 쓴다. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
