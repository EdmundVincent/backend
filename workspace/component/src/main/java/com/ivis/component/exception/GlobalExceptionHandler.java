package com.ivis.component.exception;

import com.ivis.component.web.ApiResponse;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * グローバル例外ハンドラー
 * 細粒度の例外処理を提供
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * ビジネス例外（アプリケーション固有のエラー）
     */
    @ExceptionHandler(BusinessException.class)
    public Mono<ApiResponse<String>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return Mono.just(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    /**
     * JWT 期限切れ例外
     */
    @ExceptionHandler(ExpiredJwtException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Mono<ApiResponse<String>> handleExpiredJwtException(ExpiredJwtException e) {
        log.warn("JWT token expired: {}", e.getMessage());
        return Mono.just(ApiResponse.error(401, "Token has expired. Please login again."));
    }

    /**
     * JWT 関連例外
     */
    @ExceptionHandler(JwtException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Mono<ApiResponse<String>> handleJwtException(JwtException e) {
        log.warn("JWT exception: {}", e.getMessage());
        return Mono.just(ApiResponse.error(401, "Invalid token."));
    }

    /**
     * バリデーション例外
     */
    @ExceptionHandler(WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiResponse<Map<String, String>>> handleValidationException(WebExchangeBindException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("Validation failed: {}", errors);
        return Mono.just(ApiResponse.error(400, "Validation failed"));
    }

    /**
     * 不正な引数例外
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiResponse<String>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return Mono.just(ApiResponse.error(400, e.getMessage()));
    }

    /**
     * リソースが見つからない例外
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Mono<ApiResponse<String>> handleResourceNotFoundException(ResourceNotFoundException e) {
        log.warn("Resource not found: {}", e.getMessage());
        return Mono.just(ApiResponse.error(404, e.getMessage()));
    }

    /**
     * その他の例外（フォールバック）
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ApiResponse<String>> handleException(Exception e) {
        log.error("Unexpected error occurred", e);
        // 本番環境では詳細なエラーメッセージを隠す
        String message = "An unexpected error occurred. Please try again later.";
        return Mono.just(ApiResponse.error(500, message));
    }
}
