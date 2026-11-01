package com.omkar.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class TransientFailuresTest {

    @Test
    void deadlocksAndLockConflictsAreRetryable() {
        assertThat(TransientFailures.isRetryable(new CannotAcquireLockException("deadlock"))).isTrue();
        assertThat(TransientFailures.isRetryable(
                new ObjectOptimisticLockingFailureException("Account", "id"))).isTrue();
    }

    @Test
    void rawSqlStatesInTheCauseChainAreRetryable() {
        var deadlock = new RuntimeException("commit failed", new SQLException("deadlock detected", "40P01"));
        var serialization = new RuntimeException(new SQLException("could not serialize", "40001"));
        var lockTimeout = new RuntimeException(new SQLException("lock timeout", "55P03"));
        assertThat(TransientFailures.isRetryable(deadlock)).isTrue();
        assertThat(TransientFailures.isRetryable(serialization)).isTrue();
        assertThat(TransientFailures.isRetryable(lockTimeout)).isTrue();
    }

    @Test
    void businessAndIntegrityErrorsAreNotRetryable() {
        assertThat(TransientFailures.isRetryable(ApiException.unprocessable("insufficient_funds", "x", null))).isFalse();
        assertThat(TransientFailures.isRetryable(new DataIntegrityViolationException("unique"))).isFalse();
        assertThat(TransientFailures.isRetryable(new RuntimeException(new SQLException("x", "23505")))).isFalse();
        assertThat(TransientFailures.isRetryable(new IllegalStateException("bug"))).isFalse();
    }
}
