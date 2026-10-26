package com.aworko.server.runtoken;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 팀 평가 Worker에 줄 요청별 실행 토큰 파일. tokenFile이 비어 있으면 공급하지 않는다.
 * Worker는 같은 파일을 --token-file로 읽는다(ai-agent-capstone ai_agents/cli/team_worker.py). 형식은 최종 평가와 같다.
 */
@Validated
@ConfigurationProperties(prefix = "app.team-worker")
public record TeamTokenProperties(
        String tokenFile,
        @NotNull Duration tokenTtl,
        @NotNull Duration refreshBefore,
        @NotNull Duration pollInterval) {
}
