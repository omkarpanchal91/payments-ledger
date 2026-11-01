package com.omkar.ledger.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes idempotency keys older than their retention (24h). {@code @Scheduled} ~ a Celery beat task. */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyService idempotencyService;

    public IdempotencyCleanupJob(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Scheduled(fixedDelayString = "${ledger.idempotency.cleanup-interval-ms}",
            initialDelayString = "${ledger.idempotency.cleanup-interval-ms}")
    public void deleteExpiredKeys() {
        try {
            int deleted = idempotencyService.deleteExpired();
            if (deleted > 0) {
                log.info("Deleted {} expired idempotency keys", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("Idempotency cleanup failed: {}", e.getMessage());
        }
    }
}
