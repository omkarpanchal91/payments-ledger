package com.omkar.ledger.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Custom business counters, exposed at /actuator/metrics and /actuator/prometheus.
 * Micrometer is to Spring what prometheus_client is to Python: a metrics facade.
 */
@Component
public class LedgerMetrics {

    private final MeterRegistry registry;
    private final Counter idempotentReplays;
    private final Counter transactionRetries;
    private final Counter webhookSucceeded;
    private final Counter webhookFailed;
    private final Counter webhookDead;
    private final Counter outboxPublished;

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.idempotentReplays = Counter.builder("ledger.idempotency.replays")
                .description("Requests answered from a stored idempotent response").register(registry);
        this.transactionRetries = Counter.builder("ledger.transactions.retries")
                .description("Business transactions retried after a deadlock/lock/version conflict").register(registry);
        this.webhookSucceeded = Counter.builder("ledger.webhooks.deliveries").tag("outcome", "success").register(registry);
        this.webhookFailed = Counter.builder("ledger.webhooks.deliveries").tag("outcome", "failure").register(registry);
        this.webhookDead = Counter.builder("ledger.webhooks.deliveries").tag("outcome", "dead").register(registry);
        this.outboxPublished = Counter.builder("ledger.outbox.published")
                .description("Outbox events acknowledged by Kafka").register(registry);
    }

    public void paymentCreated(String status) {
        registry.counter("ledger.payments.created", "status", status).increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void transactionRetried() {
        transactionRetries.increment();
    }

    public void webhookSucceeded() {
        webhookSucceeded.increment();
    }

    public void webhookFailed() {
        webhookFailed.increment();
    }

    public void webhookDead() {
        webhookDead.increment();
    }

    public void outboxPublished(int count) {
        outboxPublished.increment(count);
    }
}
