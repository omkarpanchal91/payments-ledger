package com.omkar.ledger.config;

/** The authenticated caller, resolved from the X-Api-Key header by {@link ApiKeyFilter}. */
public record ApiClient(String clientId, boolean admin) {
}
