package com.omkar.ledger.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Converts exceptions thrown anywhere in a controller call into the standard error JSON.
 * {@code @RestControllerAdvice} applies to all controllers - like a DRF EXCEPTION_HANDLER.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("Request failed: {}", e.getMessage(), e);
        } else {
            log.info("Request rejected: status={} code={} message={}", e.getStatus().value(), e.getCode(), e.getMessage());
        }
        return respond(e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        FieldError field = e.getBindingResult().getFieldError();
        if (field == null) {
            return respond(ApiException.badRequest("parameter_invalid", "The request body is invalid.", null));
        }
        String param = Names.toSnakeCase(field.getField());
        String message = "Invalid value for '" + param + "': " + field.getDefaultMessage() + ".";
        String code = field.getRejectedValue() == null ? "parameter_missing" : "parameter_invalid";
        return respond(ApiException.badRequest(code, message, param));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return respond(ApiException.badRequest("invalid_json",
                "The request body is not valid JSON or contains unknown/invalid fields.", null));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return respond(ApiException.badRequest("missing_header",
                "The '" + e.getHeaderName() + "' header is required.", e.getHeaderName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return respond(ApiException.badRequest("parameter_missing",
                "The '" + e.getParameterName() + "' parameter is required.", e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(ApiException.badRequest("parameter_invalid",
                "Invalid value for '" + e.getName() + "'.", e.getName()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return respond(new ApiException(HttpStatus.METHOD_NOT_ALLOWED, ErrorType.INVALID_REQUEST_ERROR,
                "method_not_allowed", "Method " + e.getMethod() + " is not supported here.", null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorType.INVALID_REQUEST_ERROR,
                "unsupported_media_type", "Use Content-Type: application/json.", null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return respond(ApiException.notFound("resource_missing", "No such endpoint: /" + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return respond(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorType.API_ERROR,
                "internal_error", "An unexpected error occurred. It is safe to retry with the same Idempotency-Key.",
                null));
    }

    private static ResponseEntity<ErrorResponse> respond(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(ErrorResponse.of(e));
    }
}
