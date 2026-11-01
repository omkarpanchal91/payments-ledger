package com.omkar.ledger.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackoffScheduleTest {

    private static final List<Duration> PRODUCTION_SCHEDULE = List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1));

    @Test
    void followsTheScheduleWithoutJitter() {
        var schedule = new BackoffSchedule(PRODUCTION_SCHEDULE, 0.0, () -> 0.5);
        assertThat(schedule.delayAfterAttempt(1)).contains(Duration.ofSeconds(10));
        assertThat(schedule.delayAfterAttempt(2)).contains(Duration.ofSeconds(30));
        assertThat(schedule.delayAfterAttempt(3)).contains(Duration.ofMinutes(2));
        assertThat(schedule.delayAfterAttempt(6)).contains(Duration.ofHours(1));
    }

    @Test
    void isDeadAfterTheLastDelay() {
        var schedule = new BackoffSchedule(PRODUCTION_SCHEDULE, 0.2, () -> 0.5);
        assertThat(schedule.maxAttempts()).isEqualTo(7);
        assertThat(schedule.delayAfterAttempt(7)).isEmpty();
        assertThat(schedule.delayAfterAttempt(100)).isEmpty();
    }

    @Test
    void jitterStaysWithinBounds() {
        var low = new BackoffSchedule(PRODUCTION_SCHEDULE, 0.2, () -> 0.0);
        var high = new BackoffSchedule(PRODUCTION_SCHEDULE, 0.2, () -> 0.999999);
        var middle = new BackoffSchedule(PRODUCTION_SCHEDULE, 0.2, () -> 0.5);
        assertThat(low.delayAfterAttempt(1)).contains(Duration.ofSeconds(8));
        assertThat(middle.delayAfterAttempt(1)).contains(Duration.ofSeconds(10));
        assertThat(high.delayAfterAttempt(1).orElseThrow()).isBetween(Duration.ofMillis(11_999), Duration.ofSeconds(12));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new BackoffSchedule(List.of(), 0.1, () -> 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffSchedule(PRODUCTION_SCHEDULE, 1.0, () -> 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        var schedule = new BackoffSchedule(PRODUCTION_SCHEDULE, 0.1, () -> 0.5);
        assertThatThrownBy(() -> schedule.delayAfterAttempt(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
