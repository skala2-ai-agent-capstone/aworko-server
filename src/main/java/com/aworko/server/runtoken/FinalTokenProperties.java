package com.aworko.server.runtoken;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 최종 평가 Worker에 줄 평가별 실행 토큰 파일. tokenFile이 비어 있으면 공급하지 않는다(로컬·테스트 기본).
 * Worker는 같은 파일을 --token-file로 읽는다(ai-agent-capstone ai_agents/integrations/final_tokens.py).
 *
 * @param tokenTtl      발급할 토큰 수명(MCP /internal/runs ttl_sec)
 * @param refreshBefore 남은 수명이 이보다 짧으면 다시 발급한다. 다시 발급하면 업무 스냅샷도 새로 고정되므로 tokenTtl보다 충분히 짧게 둔다
 * @param pollInterval  처리 중인 평가 건을 훑는 주기
 */
@Validated
@ConfigurationProperties(prefix = "app.final-worker")
public record FinalTokenProperties(
        String tokenFile,
        @NotNull Duration tokenTtl,
        @NotNull Duration refreshBefore,
        @NotNull Duration pollInterval) {

    public boolean enabled() {
        return tokenFile != null && !tokenFile.isBlank();
    }
}
