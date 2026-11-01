package com.omkar.ledger.outbox;

import java.time.Instant;
import java.util.UUID;

/** A row of outbox_events; {@code payload} is the JSON text of the event's data. */
public record OutboxEventRow(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        Instant createdAt,
        Instant publishedAt,
        int attempts) {
}
