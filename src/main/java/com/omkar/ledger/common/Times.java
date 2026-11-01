package com.omkar.ledger.common;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Times {

    private Times() {
    }

    /**
     * PostgreSQL stores timestamps with microsecond precision; Java's clock may have nanoseconds.
     * Truncating up front means the value we return in a response equals what is stored.
     */
    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
