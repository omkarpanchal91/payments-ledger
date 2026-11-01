package com.omkar.ledger.ledger;

import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.ledger.dto.PostingResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read side of the ledger, in plain SQL via JdbcTemplate (like Django's connection.cursor()).
 *
 * <p>Keyset (cursor) pagination: instead of OFFSET (which gets slower and skips/duplicates rows
 * when new postings arrive), we remember the last row and ask for rows strictly "before" it in
 * the order (created_at DESC, id DESC). The (account_id, created_at, id) index serves this directly.
 */
@Repository
public class PostingQueryRepository {

    private static final String SELECT = """
            SELECT p.id, p.journal_entry_id, p.account_id, p.amount_minor, p.created_at,
                   j.type, j.reference_id, j.description
            FROM postings p
            JOIN journal_entries j ON j.id = p.journal_entry_id
            """;

    private final JdbcTemplate jdbc;

    public PostingQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PostingResponse> findPage(UUID accountId, UUID startingAfter, int fetchSize) {
        if (startingAfter == null) {
            return jdbc.query(SELECT + """
                    WHERE p.account_id = ?
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT ?
                    """, PostingQueryRepository::map, accountId, fetchSize);
        }
        OffsetDateTime cursorCreatedAt = jdbc.query(
                "SELECT created_at FROM postings WHERE id = ? AND account_id = ?",
                rs -> rs.next() ? rs.getObject(1, OffsetDateTime.class) : null, startingAfter, accountId);
        if (cursorCreatedAt == null) {
            throw ApiException.badRequest("invalid_cursor",
                    "starting_after must be the id of a posting on this account.", "starting_after");
        }
        return jdbc.query(SELECT + """
                WHERE p.account_id = ?
                  AND (p.created_at, p.id) < (?, ?)
                ORDER BY p.created_at DESC, p.id DESC
                LIMIT ?
                """, PostingQueryRepository::map, accountId, cursorCreatedAt, startingAfter, fetchSize);
    }

    private static PostingResponse map(ResultSet rs, int rowNum) throws SQLException {
        long amount = rs.getLong("amount_minor");
        return new PostingResponse(
                rs.getObject("id", UUID.class),
                "posting",
                rs.getObject("journal_entry_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                amount,
                amount > 0 ? "DEBIT" : "CREDIT",
                JournalEntryType.valueOf(rs.getString("type")),
                rs.getObject("reference_id", UUID.class),
                rs.getString("description"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
