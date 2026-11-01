package com.omkar.ledger.idempotency;

import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.ErrorType;
import com.omkar.ledger.config.LedgerProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Storage and state machine for idempotency keys (table idempotency_keys).
 *
 * <pre>
 *   (absent) --claim--> IN_PROGRESS --complete--> COMPLETED --(24h)--> deleted
 *                          |   ^
 *              release on  |   | lock expired: another request takes over
 *              transient   v   |
 *              error    (absent)
 * </pre>
 *
 * Plain SQL is used instead of JPA because the whole design relies on atomic single statements:
 * {@code INSERT ... ON CONFLICT DO NOTHING} to claim, and conditional {@code UPDATE ... WHERE}
 * to take over or complete. Each one is race-free on its own, without extra locks.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private static final String INSERT = """
            INSERT INTO idempotency_keys
                (id, client_id, idempotency_key, endpoint, request_hash, status, lock_owner,
                 locked_until, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?,
                    now() + make_interval(secs => ?), now(), now() + make_interval(secs => ?))
            ON CONFLICT (client_id, idempotency_key, endpoint) DO NOTHING
            """;

    private static final String SELECT_EXISTING = """
            SELECT id, request_hash, status, response_status, response_body,
                   (locked_until IS NOT NULL AND locked_until > now()) AS lock_active,
                   (expires_at <= now()) AS expired
            FROM idempotency_keys
            WHERE client_id = ? AND idempotency_key = ? AND endpoint = ?
            """;

    private static final String TAKE_OVER = """
            UPDATE idempotency_keys
            SET lock_owner = ?, locked_until = now() + make_interval(secs => ?)
            WHERE id = ? AND status = 'IN_PROGRESS' AND locked_until <= now()
            """;

    private static final String COMPLETE = """
            UPDATE idempotency_keys
            SET status = 'COMPLETED', response_status = ?, response_body = ?,
                locked_until = NULL, completed_at = now()
            WHERE id = ? AND lock_owner = ? AND status = 'IN_PROGRESS'
            """;

    private record ExistingKey(UUID id, String requestHash, String status, Integer responseStatus,
                               String responseBody, boolean lockActive, boolean expired) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final double lockSeconds;
    private final double retentionSeconds;

    public IdempotencyService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                              LedgerProperties properties) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lockSeconds = properties.idempotency().lockTimeout().toMillis() / 1000.0;
        this.retentionSeconds = properties.idempotency().retention().toMillis() / 1000.0;
    }

    /**
     * Steps 2-4 of the idempotency flow, each attempt in its own short transaction (committed
     * before the business logic starts, so other requests can see the IN_PROGRESS row).
     */
    public IdempotencyClaim claim(String clientId, String key, String endpoint, String requestHash) {
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            IdempotencyClaim claim = requiresNew.execute(s -> tryClaim(clientId, key, endpoint, requestHash));
            if (claim != null) {
                return claim;
            }
        }
        throw requestInProgress();
    }

    /** Returns null when the state changed under us (row vanished / lost a takeover race) - caller retries. */
    private IdempotencyClaim tryClaim(String clientId, String key, String endpoint, String requestHash) {
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update(INSERT, id, clientId, key, endpoint, requestHash, owner, lockSeconds,
                retentionSeconds);
        if (inserted == 1) {
            return new IdempotencyClaim.Acquired(id, owner, false);
        }

        ExistingKey existing = jdbc.query(SELECT_EXISTING, rs -> rs.next()
                ? new ExistingKey(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body"),
                        rs.getBoolean("lock_active"),
                        rs.getBoolean("expired"))
                : null, clientId, key, endpoint);

        if (existing == null) {
            return null;
        }
        if (existing.expired()) {
            jdbc.update("DELETE FROM idempotency_keys WHERE id = ? AND expires_at <= now()", existing.id());
            return null;
        }
        // 4a. Same key, different body: almost certainly a client bug. Never run it.
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorType.IDEMPOTENCY_ERROR,
                    "idempotency_key_reused",
                    "This Idempotency-Key was already used with a different request body.", null);
        }
        // 4b. Finished before: return exactly what we returned the first time.
        if ("COMPLETED".equals(existing.status())) {
            return new IdempotencyClaim.Replay(existing.responseStatus(), existing.responseBody());
        }
        // 4c. Another request with this key is running right now.
        if (existing.lockActive()) {
            throw requestInProgress();
        }
        // 4d. The owner crashed (lock expired without completing). Take over atomically:
        //     only one of several racing requests can match "locked_until <= now()".
        int taken = jdbc.update(TAKE_OVER, owner, lockSeconds, existing.id());
        if (taken == 1) {
            log.warn("Took over expired idempotency key {} for client {}", key, clientId);
            return new IdempotencyClaim.Acquired(existing.id(), owner, true);
        }
        return null;
    }

    /**
     * Step 5: mark COMPLETED in the <em>caller's</em> business transaction, so money movement and
     * key completion commit or roll back together. If our lock was taken over in the meantime the
     * UPDATE matches nothing and we abort the whole business transaction.
     */
    public void complete(IdempotencyClaim.Acquired claim, StoredResponse response) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("complete() must run inside the business transaction");
        }
        int updated = jdbc.update(COMPLETE, response.status(), response.body(), claim.recordId(), claim.lockOwner());
        if (updated != 1) {
            throw ApiException.conflict("idempotency_lock_lost",
                    "This request took too long and its idempotency lock was taken over. Retry with the same key.");
        }
    }

    /** Step 6 (deterministic error): the business transaction rolled back; store the error response. */
    public void completeInNewTransaction(IdempotencyClaim.Acquired claim, StoredResponse response) {
        requiresNew.executeWithoutResult(s -> complete(claim, response));
    }

    /** Step 6 (transient error): forget the key so the client can retry it. Never throws. */
    public void release(IdempotencyClaim.Acquired claim) {
        try {
            requiresNew.executeWithoutResult(s -> jdbc.update(
                    "DELETE FROM idempotency_keys WHERE id = ? AND lock_owner = ? AND status = 'IN_PROGRESS'",
                    claim.recordId(), claim.lockOwner()));
        } catch (RuntimeException e) {
            log.error("Could not release idempotency key {}; it will expire after the lock timeout",
                    claim.recordId(), e);
        }
    }

    /** Step 7. */
    public int deleteExpired() {
        return jdbc.update("DELETE FROM idempotency_keys WHERE expires_at < now()");
    }

    private static ApiException requestInProgress() {
        return new ApiException(HttpStatus.CONFLICT, ErrorType.IDEMPOTENCY_ERROR, "request_in_progress",
                "A request with this Idempotency-Key is still being processed. Retry later.", null);
    }
}
