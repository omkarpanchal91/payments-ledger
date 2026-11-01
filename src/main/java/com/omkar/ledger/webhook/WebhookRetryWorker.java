package com.omkar.ledger.webhook;

import com.omkar.ledger.config.LedgerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Picks up PENDING/RETRYING deliveries whose next_attempt_at has passed and re-publishes DEAD
 * deliveries that could not reach the DLQ topic yet. Safe to run on many instances (SKIP LOCKED +
 * leases), like several Celery beat-driven workers sharing one queue table.
 */
@Component
public class WebhookRetryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryWorker.class);

    private final WebhookDeliveryService deliveryService;
    private final int batchSize;
    private final boolean enabled;

    public WebhookRetryWorker(WebhookDeliveryService deliveryService, LedgerProperties properties) {
        this.deliveryService = deliveryService;
        this.batchSize = properties.webhooks().batchSize();
        this.enabled = properties.webhooks().dispatcherEnabled();
    }

    @Scheduled(fixedDelayString = "${ledger.webhooks.retry-poll-interval-ms}", initialDelayString = "2000")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            int processed;
            do {
                processed = deliveryService.processDue(batchSize);
            } while (processed == batchSize);
            deliveryService.republishDeadLetters(batchSize);
        } catch (RuntimeException e) {
            log.warn("Webhook retry run failed: {}", e.toString());
        }
    }
}
