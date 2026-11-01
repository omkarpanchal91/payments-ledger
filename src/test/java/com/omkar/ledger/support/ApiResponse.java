package com.omkar.ledger.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.http.HttpHeaders;

/** An HTTP response captured by {@link LedgerApi}, with the body parsed as JSON. */
public record ApiResponse(int status, String body, HttpHeaders headers, JsonNode json) {

    public String errorCode() {
        return json.path("error").path("code").asText(null);
    }

    public String errorType() {
        return json.path("error").path("type").asText(null);
    }

    public UUID id() {
        return UUID.fromString(json.path("id").asText());
    }

    public long getLong(String field) {
        return json.path(field).asLong();
    }

    public String getText(String field) {
        return json.path(field).asText(null);
    }

    @Override
    public String toString() {
        return status + " " + body;
    }
}
