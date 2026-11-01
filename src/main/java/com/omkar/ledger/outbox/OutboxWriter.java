package com.omkar.ledger.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omkar.ledger.common.Times;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event into the outbox table <b>in the caller's transaction</b>.
 *
 * <h2>Why an outbox instead of publishing to Kafka directly?</h2>
 * "Commit to Postgres, then send to Kafka" is a <em>dual write</em>: if the process dies between
 * the two, money moved but no event exists; if you send first and the DB commit fails, consumers
 * see an event for money that never moved. Writing the event as a row in the same transaction as
 * the postings makes both atomic. A separate relay ({@link OutboxRelay}) later copies rows to Kafka.
 *
 * <p>{@code Propagation.MANDATORY} makes it impossible to write an event outside a business
 * transaction by accident.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(String aggregateType, UUID aggregateId, String eventType, Object data) {
        UUID eventId = UUID.randomUUID();
        try {
            repository.insert(eventId, aggregateType, aggregateId, eventType, objectMapper.writeValueAsString(data),
                    Times.now(clock));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event payload", e);
        }
        return eventId;
    }
}
