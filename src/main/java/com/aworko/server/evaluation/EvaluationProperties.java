package com.aworko.server.evaluation;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param staleAfter 최종 평가가 대기에 들어간 뒤(생성·재처리 시각) 이만큼 지나도 '평가 중'(대기·진행 4단계)이면 메시지를
 *                   잃었거나 Worker가 멈춘 것으로 보고 팀장 재처리를 받는다. Agent가 진행 단계를 기록하지 않으므로
 *                   실제 처리 시간·큐 대기보다 넉넉하게 둔다
 */
@Validated
@ConfigurationProperties(prefix = "app.evaluation")
public record EvaluationProperties(@NotNull Duration staleAfter) {
}
