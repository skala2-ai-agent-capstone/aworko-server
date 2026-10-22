package com.aworko.server.auth;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
        @Schema(description = "이후 요청에 Authorization: Bearer {access_token}으로 보낸다") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "만료까지 남은 초", example = "28800") long expiresIn,
        MeResponse user) {

    public static LoginResponse bearer(String accessToken, long expiresIn, MeResponse user) {
        return new LoginResponse(accessToken, "Bearer", expiresIn, user);
    }
}
