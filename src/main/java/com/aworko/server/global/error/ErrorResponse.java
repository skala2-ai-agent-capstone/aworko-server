package com.aworko.server.global.error;

import java.util.List;

public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {

    public record FieldError(String field, String reason) {
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, null);
    }

    public static ErrorResponse of(ErrorCode code, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(code.name(), message, fieldErrors);
    }
}
