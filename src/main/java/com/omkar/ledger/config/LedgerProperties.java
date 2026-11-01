package com.omkar.ledger.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of everything under {@code ledger:} in application.yml. Spring binds kebab-case keys
 * (and environment variables such as LEDGER_FEES_PERCENT_BPS) onto these records at startup -
 * think of it as a validated, typed version of Django's settings.py.
 */
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        Security security,
        Fees fees,
        Idempotency idempotency,
        Transactions transactions,
        Outbox outbox,
        Kafka kafka,
        Webhooks webhooks) {

    public record Security(List<ApiKey> apiKeys) {
    }

    public record ApiKey(String key, String clientId, boolean admin) {
    }

    /** Fee = round_half_up(amount * percentBps / 10_000) + fixedMinor. */
    public record Fees(int percentBps, long fixedMinor) {
    }

    public record Idempotency(Duration lockTimeout, Duration retention, long cleanupIntervalMs) {
    }

    public record Transactions(int maxRetries, Duration baseBackoff) {
    }

    public record Outbox(boolean enabled, long relayIntervalMs, int batchSize, Duration sendTimeout) {
    }

    public record Kafka(String eventsTopic, String dlqTopic, int partitions, int replicationFactor) {
    }

    public record Webhooks(
            boolean dispatcherEnabled,
            String consumerGroup,
            boolean deliverInline,
            Duration requestTimeout,
            List<Duration> retrySchedule,
            double jitterRatio,
            long retryPollIntervalMs,
            int batchSize,
            Duration claimLease) {
    }
}
