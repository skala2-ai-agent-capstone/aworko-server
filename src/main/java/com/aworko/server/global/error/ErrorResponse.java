package com.aworko.server.global.error;

import java.util.List;

/** retryAfterSeconds는 429(요청 제한)일 때만 채운다. */
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors, Long retryAfterSeconds) {

    public record FieldError(String field, String reason) {
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, null, null);
    }

    public static ErrorResponse of(ErrorCode code, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(code.name(), message, fieldErrors, null);
    }

    public static ErrorResponse tooManyRequests(String message, long retryAfterSeconds) {
        return new ErrorResponse(ErrorCode.TOO_MANY_REQUESTS.name(), message, null, retryAfterSeconds);
    }
}
