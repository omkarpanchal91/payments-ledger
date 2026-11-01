package com.omkar.ledger.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private static final String COLUMNS =
            "id, aggregate_type, aggregate_id, event_type, payload::text AS payload, created_at, published_at, attempts";

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String aggregateType, UUID aggregateId, String eventType, String payloadJson,
                       Instant createdAt) {
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, created_at, attempts)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, 0)
                """, id, aggregateType, aggregateId, eventType, payloadJson, Timestamp.from(createdAt));
    }

    /**
     * Claims the oldest unpublished events. Must run inside a transaction: the row locks are held
     * until it commits. {@code SKIP LOCKED} makes a second relay instance skip rows the first one
     * is publishing instead of waiting for them, so several app instances can relay in parallel.
     */
    public List<OutboxEventRow> lockUnpublishedBatch(int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, OutboxRepository::map, limit);
    }

    public void markPublished(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1, last_error = NULL "
                + "WHERE id = ?", ids.stream().map(id -> new Object[]{id}).toList());
    }

    public void markFailed(UUID id, String error) {
        jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                truncate(error), id);
    }

    public Optional<OutboxEventRow> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM outbox_events WHERE id = ?", OutboxRepository::map, id)
                .stream().findFirst();
    }

    public long countUnpublished() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    private static OutboxEventRow map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime published = rs.getObject("published_at", OffsetDateTime.class);
        return new OutboxEventRow(
                rs.getObject("id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                published == null ? null : published.toInstant(),
                rs.getInt("attempts"));
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
