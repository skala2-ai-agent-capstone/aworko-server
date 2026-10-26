package com.aworko.server.aggregation;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param staleAfter 이만큼 지나도 리포트가 없고 실패 기록도 없으면 Worker가 없거나 멈춘 것으로 보고 실패(TIMED_OUT)로 보인다.
 *                   실행 토큰도 더 공급하지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "app.team-evaluation")
public record TeamEvaluationProperties(@NotNull Duration staleAfter) {
}
