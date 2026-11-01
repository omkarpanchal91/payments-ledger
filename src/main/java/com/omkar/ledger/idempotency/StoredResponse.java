package com.omkar.ledger.idempotency;

/** The HTTP status and exact JSON body saved for an idempotency key. */
public record StoredResponse(int status, String body) {
}
