package com.aworko.server.global.error;

import com.aworko.server.global.common.ApiResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimit(RateLimitException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(ApiResponse.failure(ErrorResponse.tooManyRequests(e.getMessage(), e.getRetryAfterSeconds())));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        return respond(e.getErrorCode(), ErrorResponse.of(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return respond(code, ErrorResponse.of(code, code.getDefaultMessage(), fields));
    }

    /** 업로드 한도(spring.servlet.multipart) 초과 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return respond(code, ErrorResponse.of(code, "파일은 20MB까지, 한 번에 60MB까지 올릴 수 있습니다"));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return respond(code, ErrorResponse.of(code, code.getDefaultMessage()));
    }

    /** @PreAuthorize 거부는 필터가 아니라 컨트롤러 단계에서 나므로 여기서 403으로 바꾼다. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        ErrorCode code = ErrorCode.FORBIDDEN;
        return respond(code, ErrorResponse.of(code, code.getDefaultMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        ErrorCode code = ErrorCode.NOT_FOUND;
        return respond(code, ErrorResponse.of(code, code.getDefaultMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        // 오류 본문에 내부 정보·민감정보가 섞일 수 있으므로 응답에는 일반 문구만 내린다.
        log.error("Unhandled exception", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return respond(code, ErrorResponse.of(code, code.getDefaultMessage()));
    }

    private ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, ErrorResponse body) {
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.failure(body));
    }
}
