package com.omkar.ledger.outbox;

import com.omkar.ledger.common.LedgerMetrics;
import com.omkar.ledger.config.LedgerProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Copies outbox rows to Kafka (topic ledger.events, key = aggregate id).
 *
 * <ol>
 *   <li>Lock up to batch-size unpublished rows (FOR UPDATE SKIP LOCKED).</li>
 *   <li>Send them all, then wait for the broker acks (idempotent producer, acks=all).</li>
 *   <li>Mark acked rows published in the same transaction, then commit.</li>
 * </ol>
 *
 * <b>Delivery guarantee: at-least-once.</b> If we crash after Kafka acked but before the commit,
 * the rows are still unpublished and get sent again. Consumers must therefore dedupe by event id
 * (the webhook dispatcher does, with UNIQUE(event_id, endpoint_id)). The idempotent producer only
 * removes duplicates caused by the producer's own internal retries, not by our process restarting.
 *
 * <p>If Kafka is down, sends fail fast (max.block.ms), rows stay unpublished, the API keeps working
 * and the backlog drains automatically when Kafka comes back.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final EventService eventService;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactionTemplate;
    private final LedgerMetrics metrics;
    private final LedgerProperties.Outbox config;
    private final String topic;

    public OutboxRelay(OutboxRepository repository, EventService eventService, KafkaTemplate<String, String> kafka,
                       PlatformTransactionManager transactionManager, LedgerMetrics metrics,
                       LedgerProperties properties) {
        this.repository = repository;
        this.eventService = eventService;
        this.kafka = kafka;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.config = properties.outbox();
        this.topic = properties.kafka().eventsTopic();
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.relay-interval-ms}", initialDelayString = "1000")
    public void relayPending() {
        if (!config.enabled()) {
            return;
        }
        try {
            int published;
            do {
                published = relayBatch();
            } while (published == config.batchSize());
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed: {}", e.toString());
        }
    }

    /** Publishes one batch; returns how many rows were acknowledged by Kafka. */
    public int relayBatch() {
        Integer published = transactionTemplate.execute(status -> {
            List<OutboxEventRow> rows = repository.lockUnpublishedBatch(config.batchSize());
            if (rows.isEmpty()) {
                return 0;
            }
            List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(rows.size());
            for (OutboxEventRow row : rows) {
                CompletableFuture<SendResult<String, String>> future = send(row);
                futures.add(future);
                if (future.isCompletedExceptionally()) {
                    break; // broker unreachable: don't wait max.block.ms for every remaining row
                }
            }

            List<UUID> acked = new ArrayList<>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                OutboxEventRow row = rows.get(i);
                try {
                    futures.get(i).get(config.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    acked.add(row.id());
                } catch (ExecutionException | TimeoutException e) {
                    Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    log.warn("Failed to publish outbox event {}: {}", row.id(), cause.toString());
                    repository.markFailed(row.id(), cause.toString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            repository.markPublished(acked);
            return acked.size();
        });
        int count = published == null ? 0 : published;
        if (count > 0) {
            metrics.outboxPublished(count);
            log.debug("Published {} outbox events", count);
        }
        return count;
    }

    private CompletableFuture<SendResult<String, String>> send(OutboxEventRow row) {
        try {
            String value = eventService.toJson(eventService.toEnvelope(row));
            return kafka.send(topic, row.aggregateId().toString(), value);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
