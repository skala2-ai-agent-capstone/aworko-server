package com.aworko.server.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
        @NotNull Outbox outbox,
        @NotNull Duration evaluationRetryDelay,
        @NotNull Duration liveEvaluationRetryDelay) {

    public record Outbox(boolean enabled, @NotNull Duration pollInterval, @Positive int batchSize,
                         @NotNull Duration confirmTimeout) {
    }
}
