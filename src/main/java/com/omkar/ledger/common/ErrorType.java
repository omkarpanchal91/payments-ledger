package com.omkar.ledger.common;

/** The "type" field of the error body; tells clients which family of error happened. */
public enum ErrorType {
    INVALID_REQUEST_ERROR("invalid_request_error"),
    AUTHENTICATION_ERROR("authentication_error"),
    PERMISSION_ERROR("permission_error"),
    IDEMPOTENCY_ERROR("idempotency_error"),
    CONFLICT_ERROR("conflict_error"),
    API_ERROR("api_error");

    private final String value;

    ErrorType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
