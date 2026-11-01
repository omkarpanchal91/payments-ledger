package com.omkar.ledger.reconciliation;

import com.omkar.ledger.common.Times;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the ledger's invariants from scratch, using only the append-only postings as truth.
 *
 * <p>Runs in a REPEATABLE READ, read-only transaction: all queries see one consistent snapshot of
 * the database even while transfers keep committing, so a balance updated "between" two queries
 * can't show up as a false mismatch.
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReconciliationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run() {
        List<ReconciliationReport.AccountMismatch> accountMismatches = jdbc.query("""
                SELECT a.id, a.code, a.balance_minor, COALESCE(SUM(p.amount_minor), 0) AS postings_sum
                FROM accounts a
                LEFT JOIN postings p ON p.account_id = a.id
                GROUP BY a.id, a.code, a.balance_minor
                HAVING a.balance_minor <> COALESCE(SUM(p.amount_minor), 0)
                ORDER BY a.code
                """, (rs, n) -> new ReconciliationReport.AccountMismatch(
                rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getLong("balance_minor"), rs.getLong("postings_sum")));

        List<ReconciliationReport.UnbalancedEntry> unbalancedEntries = jdbc.query("""
                SELECT journal_entry_id, SUM(amount_minor) AS total
                FROM postings
                GROUP BY journal_entry_id
                HAVING SUM(amount_minor) <> 0
                """, (rs, n) -> new ReconciliationReport.UnbalancedEntry(
                rs.getObject("journal_entry_id", UUID.class), rs.getLong("total")));

        List<ReconciliationReport.CurrencyImbalance> currencyImbalances = jdbc.query("""
                SELECT a.currency, SUM(p.amount_minor) AS total
                FROM postings p
                JOIN accounts a ON a.id = p.account_id
                GROUP BY a.currency
                HAVING SUM(p.amount_minor) <> 0
                """, (rs, n) -> new ReconciliationReport.CurrencyImbalance(
                rs.getString("currency").trim(), rs.getLong("total")));

        long accounts = count("SELECT count(*) FROM accounts");
        long entries = count("SELECT count(*) FROM journal_entries");
        boolean balanced = accountMismatches.isEmpty() && unbalancedEntries.isEmpty() && currencyImbalances.isEmpty();
        return new ReconciliationReport(balanced, Times.now(clock), accounts, entries, accountMismatches,
                unbalancedEntries, currencyImbalances);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
