package com.omkar.ledger.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * The public shape of an event, used as the Kafka message value, the webhook body and the
 * GET /v1/events/{id} response.
 *
 * @param id      event id; consumers dedupe on it (delivery is at-least-once)
 * @param created unix seconds
 */
public record EventEnvelope(UUID id, String type, long created, JsonNode data) {
}
