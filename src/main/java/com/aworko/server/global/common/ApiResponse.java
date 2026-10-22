package com.aworko.server.global.common;

import com.aworko.server.global.error.ErrorResponse;

/**
 * 모든 API의 공통 응답 봉투. MCP 응답 봉투({ok, data} / {ok, error})와 같은 모양으로 맞춘다.
 */
public record ApiResponse<T>(boolean ok, T data, ErrorResponse error) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> failure(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }
}
