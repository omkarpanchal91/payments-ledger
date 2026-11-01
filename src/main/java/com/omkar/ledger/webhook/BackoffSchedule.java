package com.omkar.ledger.webhook;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * Retry delays for failed webhook deliveries, e.g. 10s, 30s, 2m, 10m, 30m, 1h.
 *
 * <p>Attempt 1 is the first delivery. After attempt n fails we wait {@code delays[n-1]}, randomized by
 * &plusmn;jitterRatio so that thousands of deliveries that failed together (merchant outage) don't
 * all retry in the same second. After the last delay is used up the delivery is dead.
 * Total attempts = delays.size() + 1.
 */
public final class BackoffSchedule {

    private final List<Duration> delays;
    private final double jitterRatio;
    private final DoubleSupplier random;

    /** @param random returns a value in [0, 1); injectable so tests are deterministic */
    public BackoffSchedule(List<Duration> delays, double jitterRatio, DoubleSupplier random) {
        if (delays == null || delays.isEmpty()) {
            throw new IllegalArgumentException("At least one retry delay is required");
        }
        if (jitterRatio < 0 || jitterRatio >= 1) {
            throw new IllegalArgumentException("jitterRatio must be in [0, 1)");
        }
        this.delays = List.copyOf(delays);
        this.jitterRatio = jitterRatio;
        this.random = random;
    }

    public int maxAttempts() {
        return delays.size() + 1;
    }

    /** @return delay before the next attempt, or empty if {@code attemptsMade} exhausted the schedule */
    public Optional<Duration> delayAfterAttempt(int attemptsMade) {
        if (attemptsMade < 1) {
            throw new IllegalArgumentException("attemptsMade must be >= 1");
        }
        if (attemptsMade > delays.size()) {
            return Optional.empty();
        }
        long baseMillis = delays.get(attemptsMade - 1).toMillis();
        double factor = 1.0 + jitterRatio * (2.0 * random.getAsDouble() - 1.0);
        return Optional.of(Duration.ofMillis(Math.max(0L, Math.round(baseMillis * factor))));
    }
}
