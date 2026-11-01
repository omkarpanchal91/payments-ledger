package com.omkar.ledger.common;

/** Names shared by the request-id filter, logging and error responses. */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";
    public static final String CLIENT_MDC_KEY = "client_id";

    private RequestIds() {
    }
}
