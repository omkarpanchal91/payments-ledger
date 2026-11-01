package com.omkar.ledger.idempotency;

import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.config.ApiClient;
import com.omkar.ledger.config.ApiKeyFilter;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Everything that identifies one idempotent call: who (client), which key, which endpoint and the
 * request body whose hash must match on a retry.
 */
public record IdempotentRequest(String clientId, String idempotencyKey, String endpoint, Object body) {

    public static final String HEADER = "Idempotency-Key";
    public static final int MAX_KEY_LENGTH = 255;

    public static IdempotentRequest of(HttpServletRequest http, String idempotencyKey, Object body) {
        if (!(http.getAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) instanceof ApiClient client)) {
            throw ApiException.unauthorized("invalid_api_key", "Missing or invalid API key.");
        }
        return new IdempotentRequest(client.clientId(), idempotencyKey,
                http.getMethod() + " " + http.getRequestURI(), body);
    }
}
