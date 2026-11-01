package com.omkar.ledger.common;

import org.springframework.http.HttpStatus;

/**
 * A failure that maps to a well-defined HTTP error response. Services throw it; the
 * {@link GlobalExceptionHandler} (like a DRF exception handler) turns it into JSON.
 *
 * <p>Errors in the 4xx range except 409 are <em>deterministic</em>: retrying the same request gives
 * the same answer, so the idempotency layer stores them. 409 and 5xx are <em>transient</em>: the
 * idempotency key is released so the client can retry.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorType type;
    private final String code;
    private final String param;

    public ApiException(HttpStatus status, ErrorType type, String code, String message, String param) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.param = param;
    }

    public static ApiException badRequest(String code, String message, String param) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorType.INVALID_REQUEST_ERROR, code, message, param);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorType.INVALID_REQUEST_ERROR, code, message, null);
    }

    public static ApiException unprocessable(String code, String message, String param) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorType.INVALID_REQUEST_ERROR, code, message, param);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorType.CONFLICT_ERROR, code, message, null);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorType.AUTHENTICATION_ERROR, code, message, null);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorType.PERMISSION_ERROR, code, message, null);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorType getType() {
        return type;
    }

    public String getCode() {
        return code;
    }

    public String getParam() {
        return param;
    }

    /** True when the client may retry the same request (same idempotency key) and get a different result. */
    public boolean isClientRetryable() {
        return status == HttpStatus.CONFLICT || status.is5xxServerError();
    }
}
