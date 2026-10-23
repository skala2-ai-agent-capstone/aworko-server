package com.aworko.server.global.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 업무 공간 대화용 LLM. llm-proxy(OpenAI 호환)를 거쳐야 대화가 llm_exchanges(→ messages 뷰)에 기록된다. */
@Validated
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        @NotBlank(message = "app.llm.base-url(LLM_PROXY_URL)이 필요합니다") String baseUrl,
        @NotBlank(message = "app.llm.api-key(LLM_PROXY_KEY)가 필요합니다") String apiKey,
        @NotBlank String model,
        @NotNull Duration timeout) {
}
