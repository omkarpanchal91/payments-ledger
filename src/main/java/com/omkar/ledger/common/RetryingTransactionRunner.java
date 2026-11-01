package com.omkar.ledger.common;

import com.omkar.ledger.config.LedgerProperties;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of work in one database transaction and retries the whole transaction on transient
 * concurrency failures (deadlock, serialization failure, lock timeout, optimistic-lock conflict).
 *
 * <p>{@link TransactionTemplate} is the programmatic form of {@code @Transactional}; it is the
 * closest thing to Django's {@code with transaction.atomic():}. Every attempt gets a brand-new
 * transaction and persistence context, so a retry never sees stale entities from the failed try
 * (this is also why spring.jpa.open-in-view is disabled).
 *
 * <p>After {@code maxRetries} retries the failure becomes a 409 so the client retries later.
 */
@Component
public class RetryingTransactionRunner {

    private static final Logger log = LoggerFactory.getLogger(RetryingTransactionRunner.class);

    private final TransactionTemplate transactionTemplate;
    private final int maxRetries;
    private final Duration baseBackoff;
    private final LedgerMetrics metrics;

    public RetryingTransactionRunner(PlatformTransactionManager transactionManager,
                                     LedgerProperties properties,
                                     LedgerMetrics metrics) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxRetries = properties.transactions().maxRetries();
        this.baseBackoff = properties.transactions().baseBackoff();
        this.metrics = metrics;
    }

    public <T> T inTransaction(Supplier<T> work) {
        int retries = 0;
        while (true) {
            try {
                return transactionTemplate.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (!TransientFailures.isRetryable(e)) {
                    throw e;
                }
                if (retries >= maxRetries) {
                    log.warn("Giving up after {} retries: {}", retries, e.toString());
                    throw ApiException.conflict("concurrent_modification",
                            "The request conflicted with concurrent updates. Retry with the same Idempotency-Key.");
                }
                retries++;
                metrics.transactionRetried();
                log.info("Transient failure ({}), retry {}/{}", e.getClass().getSimpleName(), retries, maxRetries);
                sleepBeforeRetry(retries);
            }
        }
    }

    /** Exponential backoff with full jitter: random in [0, base * 2^(n-1)] plus base. */
    private void sleepBeforeRetry(int retry) {
        long base = Math.max(1, baseBackoff.toMillis());
        long cap = base * (1L << Math.min(retry - 1, 10));
        long sleep = base + ThreadLocalRandom.current().nextLong(cap + 1);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry a transaction", ie);
        }
    }
}
