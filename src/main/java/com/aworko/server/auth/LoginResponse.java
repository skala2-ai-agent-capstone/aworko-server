package com.aworko.server.auth;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, MeResponse user) {

    public static LoginResponse bearer(String accessToken, long expiresIn, MeResponse user) {
        return new LoginResponse(accessToken, "Bearer", expiresIn, user);
    }
}
