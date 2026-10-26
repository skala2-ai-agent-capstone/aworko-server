package com.aworko.server.global.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** business-mcp 내부 API(/internal/*). X-Internal-Key는 백엔드만 안다(MCP의 INTERNAL_API_KEY와 같은 값). */
@Validated
@ConfigurationProperties(prefix = "app.mcp")
public record McpProperties(
        @NotBlank(message = "app.mcp.base-url(MCP_INTERNAL_URL)이 필요합니다") String baseUrl,
        @NotBlank(message = "app.mcp.internal-key(INTERNAL_API_KEY)가 필요합니다") String internalKey,
        @NotNull Duration timeout) {
}
