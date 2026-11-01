package com.omkar.ledger.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omkar.ledger.common.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Builds the public event envelope from an outbox row. */
@Service
public class EventService {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public EventService(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public EventEnvelope get(UUID id) {
        return repository.findById(id).map(this::toEnvelope)
                .orElseThrow(() -> ApiException.notFound("event_not_found", "No event with id " + id + "."));
    }

    public EventEnvelope toEnvelope(OutboxEventRow row) {
        try {
            return new EventEnvelope(row.id(), row.eventType(), row.createdAt().getEpochSecond(),
                    objectMapper.readTree(row.payload()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt outbox payload for event " + row.id(), e);
        }
    }

    public String toJson(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + envelope.id(), e);
        }
    }
}
