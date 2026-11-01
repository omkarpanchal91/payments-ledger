package com.omkar.ledger.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * webhook_deliveries, one row per (event, endpoint).
 *
 * <p>Claiming uses a <b>lease</b>: we atomically push next_attempt_at into the future (now + lease)
 * and commit, then do the slow HTTP call without holding any DB lock. If the worker dies mid-call,
 * the lease expires and another worker picks the row up again. (Holding SELECT ... FOR UPDATE
 * during a 5-second HTTP call would tie up a connection and block other workers.)
 */
@Repository
public class WebhookDeliveryRepository {

    private static final String RETURNING = " RETURNING id, event_id, endpoint_id, attempt_count";

    private final JdbcTemplate jdbc;

    public WebhookDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the new row id, or empty if a delivery for this (event, endpoint) already exists. */
    public Optional<UUID> insertIfAbsent(UUID eventId, UUID endpointId) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO webhook_deliveries
                    (id, event_id, endpoint_id, status, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', 0, now(), now(), now())
                ON CONFLICT (event_id, endpoint_id) DO NOTHING
                """, id, eventId, endpointId);
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    public Optional<ClaimedDelivery> claimOne(UUID id, Duration lease) {
        return jdbc.query("""
                UPDATE webhook_deliveries
                SET next_attempt_at = now() + make_interval(secs => ?), updated_at = now()
                WHERE id = ? AND status IN ('PENDING', 'RETRYING') AND next_attempt_at <= now()
                """ + RETURNING, WebhookDeliveryRepository::map, seconds(lease), id).stream().findFirst();
    }

    public List<ClaimedDelivery> claimDue(int limit, Duration lease) {
        return jdbc.query("""
                UPDATE webhook_deliveries
                SET next_attempt_at = now() + make_interval(secs => ?), updated_at = now()
                WHERE id IN (
                    SELECT id FROM webhook_deliveries
                    WHERE status IN ('PENDING', 'RETRYING') AND next_attempt_at <= now()
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                """ + RETURNING, WebhookDeliveryRepository::map, seconds(lease), limit);
    }

    public void markSucceeded(UUID id, int attemptCount, int responseCode) {
        jdbc.update("""
                UPDATE webhook_deliveries
                SET status = 'SUCCEEDED', attempt_count = ?, last_response_code = ?, last_error = NULL,
                    updated_at = now()
                WHERE id = ?
                """, attemptCount, responseCode, id);
    }

    public void markRetrying(UUID id, int attemptCount, Integer responseCode, String error, Duration delay) {
        jdbc.update("""
                UPDATE webhook_deliveries
                SET status = 'RETRYING', attempt_count = ?, last_response_code = ?, last_error = ?,
                    next_attempt_at = now() + make_interval(secs => ?), updated_at = now()
                WHERE id = ?
                """, ps -> {
            ps.setInt(1, attemptCount);
            setNullableInt(ps, 2, responseCode);
            ps.setString(3, truncate(error));
            ps.setDouble(4, seconds(delay));
            ps.setObject(5, id);
        });
    }

    public void markDead(UUID id, int attemptCount, Integer responseCode, String error) {
        jdbc.update("""
                UPDATE webhook_deliveries
                SET status = 'DEAD', attempt_count = ?, last_response_code = ?, last_error = ?, updated_at = now()
                WHERE id = ?
                """, ps -> {
            ps.setInt(1, attemptCount);
            setNullableInt(ps, 2, responseCode);
            ps.setString(3, truncate(error));
            ps.setObject(4, id);
        });
    }

    public void markDlqPublished(UUID id) {
        jdbc.update("UPDATE webhook_deliveries SET dlq_published_at = now(), updated_at = now() WHERE id = ?", id);
    }

    /** DEAD deliveries whose DLQ publish failed earlier (e.g. Kafka was down). */
    public List<UUID> findDeadNotInDlq(int limit) {
        return jdbc.queryForList("""
                SELECT id FROM webhook_deliveries
                WHERE status = 'DEAD' AND dlq_published_at IS NULL
                ORDER BY updated_at
                LIMIT ?
                """, UUID.class, limit);
    }

    public Optional<DeadDelivery> findDead(UUID id) {
        return jdbc.query("""
                SELECT id, event_id, endpoint_id, attempt_count, last_response_code, last_error
                FROM webhook_deliveries WHERE id = ? AND status = 'DEAD'
                """, (rs, n) -> new DeadDelivery(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getInt("attempt_count"),
                (Integer) rs.getObject("last_response_code"),
                rs.getString("last_error")), id).stream().findFirst();
    }

    public record DeadDelivery(UUID id, UUID eventId, UUID endpointId, int attemptCount, Integer lastResponseCode,
                               String lastError) {
    }

    private static ClaimedDelivery map(ResultSet rs, int rowNum) throws SQLException {
        return new ClaimedDelivery(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getInt("attempt_count"));
    }

    private static void setNullableInt(java.sql.PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
