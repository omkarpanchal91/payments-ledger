package com.omkar.ledger.common;

import org.slf4j.MDC;

/**
 * The single error format used everywhere:
 * <pre>{ "error": { "type": "...", "code": "...", "message": "...", "param": "...", "request_id": "..." } }</pre>
 * Field names become snake_case through the global Jackson naming strategy; null fields are omitted.
 */
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String type, String code, String message, String param, String requestId) {
    }

    public static ErrorResponse of(ApiException e) {
        return new ErrorResponse(new ErrorBody(
                e.getType().value(), e.getCode(), e.getMessage(), e.getParam(), MDC.get(RequestIds.MDC_KEY)));
    }
}
