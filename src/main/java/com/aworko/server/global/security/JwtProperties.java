package com.aworko.server.global.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank(message = "app.jwt.secret(JWT_SECRET)이 필요합니다") String secret,
        @NotNull Duration accessTokenTtl,
        @NotBlank String issuer) {
}
