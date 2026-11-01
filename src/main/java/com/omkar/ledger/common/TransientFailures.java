package com.omkar.ledger.common;

import java.sql.SQLException;
import java.util.Set;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * Decides whether a failed transaction is worth retrying as-is.
 *
 * <p>Retryable: deadlocks (SQLSTATE 40P01), serialization failures (40001), lock timeouts (55P03)
 * and optimistic-lock conflicts (@Version mismatch). Spring translates most of these into
 * {@link ConcurrencyFailureException} subclasses; we also walk the cause chain for the raw SQLSTATE
 * because some failures surface at commit time wrapped in other exception types.
 */
public final class TransientFailures {

    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40001", "40P01", "55P03");

    private TransientFailures() {
    }

    public static boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ApiException) {
                return false;
            }
            if (t instanceof ConcurrencyFailureException
                    || t instanceof jakarta.persistence.OptimisticLockException
                    || t instanceof jakarta.persistence.PessimisticLockException
                    || t instanceof jakarta.persistence.LockTimeoutException
                    || t instanceof org.hibernate.StaleStateException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null
                    && RETRYABLE_SQL_STATES.contains(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
