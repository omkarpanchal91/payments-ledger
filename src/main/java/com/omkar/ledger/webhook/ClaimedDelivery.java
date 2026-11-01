package com.omkar.ledger.webhook;

import java.util.UUID;

/** A delivery this worker has leased and is about to attempt. */
public record ClaimedDelivery(UUID id, UUID eventId, UUID endpointId, int attemptCount) {
}
