package com.omkar.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.KafkaTestConsumer;
import com.omkar.ledger.support.TestIds;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private TransactionTemplate tx;

    private long eventCount(UUID aggregateId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class,
                aggregateId);
        return n == null ? 0 : n;
    }

    @Test
    void eventIsDiscardedWhenTheBusinessTransactionRollsBack() {
        UUID aggregate = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            outboxWriter.write("test", aggregate, "test.event", Map.of("x", 1));
            throw new IllegalStateException("business logic failed after writing the event");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(eventCount(aggregate)).isZero();
    }

    @Test
    void eventIsKeptWhenTheTransactionCommits() {
        UUID aggregate = UUID.randomUUID();
        tx.executeWithoutResult(s -> outboxWriter.write("test", aggregate, "test.event", Map.of("x", 1)));
        assertThat(eventCount(aggregate)).isEqualTo(1);
    }

    @Test
    void writingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outboxWriter.write("test", UUID.randomUUID(), "test.event", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void failedTransferWritesNoEvent() {
        UUID from = api.createWallet(TestIds.unique("w"), 10);
        UUID to = api.createWallet(TestIds.unique("w"), 0);
        assertThat(api.transfer(from, to, 1_000).status()).isEqualTo(422);
        Long events = jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE event_type = 'transfer.completed' AND payload->>'from_account_id' = ?
                """, Long.class, from.toString());
        assertThat(events).isZero();
    }

    @Test
    void relayPublishesToKafkaAndMarksTheRowPublished() throws Exception {
        UUID from = api.createWallet(TestIds.unique("w"), 1_000);
        UUID to = api.createWallet(TestIds.unique("w"), 0);
        ApiResponse transfer = api.transfer(from, to, 250);
        UUID transferId = transfer.id();
        UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?", UUID.class,
                transferId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?", Boolean.class, eventId)).isTrue());

        ConsumerRecord<String, String> record = KafkaTestConsumer.pollFor(kafkaBootstrapServers(), "ledger.events",
                        r -> r.value().contains(eventId.toString()), Duration.ofSeconds(30))
                .orElseThrow(() -> new AssertionError("event " + eventId + " not found on ledger.events"));

        assertThat(record.key()).isEqualTo(transferId.toString());
        JsonNode envelope = objectMapper.readTree(record.value());
        assertThat(envelope.path("id").asText()).isEqualTo(eventId.toString());
        assertThat(envelope.path("type").asText()).isEqualTo("transfer.completed");
        assertThat(envelope.path("created").asLong()).isPositive();
        assertThat(envelope.path("data").path("amount_minor").asLong()).isEqualTo(250);
    }

    @Test
    void eventsCanBeFetchedByIdOverTheApi() {
        UUID from = api.createWallet(TestIds.unique("w"), 1_000);
        UUID to = api.createWallet(TestIds.unique("w"), 0);
        UUID transferId = api.transfer(from, to, 10).id();
        UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?", UUID.class,
                transferId);

        ApiResponse event = api.get("/v1/events/" + eventId);
        assertThat(event.status()).isEqualTo(200);
        assertThat(event.getText("type")).isEqualTo("transfer.completed");
        assertThat(event.json().path("data").path("id").asText()).isEqualTo(transferId.toString());

        assertThat(api.get("/v1/events/" + UUID.randomUUID()).status()).isEqualTo(404);
    }
}
