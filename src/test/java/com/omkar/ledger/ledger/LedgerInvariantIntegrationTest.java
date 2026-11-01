package com.omkar.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.TestIds;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** The double-entry rules, enforced both by LedgerService and by the database itself. */
class LedgerInvariantIntegrationTest extends IntegrationTestBase {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void serviceRejectsAnUnbalancedEntry() {
        UUID a = api.createWallet(TestIds.unique("w"), 1_000);
        UUID b = api.createWallet(TestIds.unique("w"), 0);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post(new LedgerEntryRequest(
                JournalEntryType.TRANSFER, null, "broken", "USD",
                List.of(Leg.debit(a, 100), Leg.credit(b, 50))))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("sum to 50");

        assertThat(api.balance(a)).isEqualTo(1_000);
        assertThat(api.balance(b)).isZero();
    }

    @Test
    void serviceRequiresAnExistingTransaction() {
        UUID a = api.createWallet(TestIds.unique("w"), 1_000);
        UUID b = api.createWallet(TestIds.unique("w"), 0);
        assertThatThrownBy(() -> ledger.post(new LedgerEntryRequest(JournalEntryType.TRANSFER, null, null, "USD",
                List.of(Leg.decrease(a, 1), Leg.increase(b, 1)))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void insufficientFundsChangesNothing() {
        UUID a = api.createWallet(TestIds.unique("w"), 1_000);
        UUID b = api.createWallet(TestIds.unique("w"), 0);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post(new LedgerEntryRequest(
                JournalEntryType.TRANSFER, null, null, "USD",
                List.of(Leg.decrease(a, 5_000), Leg.increase(b, 5_000))))))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(api.balance(a)).isEqualTo(1_000);
        assertThat(postingCount(b)).isZero();
    }

    @Test
    void databaseRejectsAnUnbalancedEntryAtCommit() {
        UUID a = api.createWallet(TestIds.unique("w"), 0);
        UUID b = api.createWallet(TestIds.unique("w"), 0);
        UUID entry = UUID.randomUUID();

        // Bypass LedgerService entirely, as a buggy code path or a manual SQL session would.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(entry, "USD");
            insertPosting(entry, a, 100);
            insertPosting(entry, b, -50);
        })).rootCause().hasMessageContaining("unbalanced");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE id = ?", Long.class, entry))
                .isZero();
    }

    @Test
    void databaseRejectsASinglePostingEntry() {
        UUID a = api.createWallet(TestIds.unique("w"), 0);
        UUID entry = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(entry, "USD");
            insertPosting(entry, a, 100);
        })).rootCause().hasMessageContaining("at least 2");
    }

    @Test
    void databaseRejectsMixedCurrencies() {
        UUID usd = api.createWallet(TestIds.unique("w"), 0);
        UUID eur = api.createAccount(TestIds.unique("w_eur"), "LIABILITY", "EUR", false).id();
        UUID entry = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(entry, "USD");
            insertPosting(entry, usd, 100);
            insertPosting(entry, eur, -100);
        })).rootCause().hasMessageContaining("mixes currencies");
    }

    @Test
    void databaseAcceptsABalancedEntry() {
        UUID a = api.createWallet(TestIds.unique("w"), 0);
        UUID b = api.createWallet(TestIds.unique("w"), 0);
        UUID entry = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            insertEntry(entry, "USD");
            insertPosting(entry, a, 100);
            insertPosting(entry, b, -100);
            // keep cached balances consistent so reconciliation stays clean
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor + 100 WHERE id = ?", a);
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor - 100 WHERE id = ?", b);
        });
        assertThat(postingCount(a)).isEqualTo(1);
    }

    @Test
    void postingsAndEntriesAreAppendOnly() {
        UUID wallet = api.createWallet(TestIds.unique("w"), 500);
        UUID postingId = jdbc.queryForObject("SELECT id FROM postings WHERE account_id = ?", UUID.class, wallet);
        UUID entryId = jdbc.queryForObject("SELECT journal_entry_id FROM postings WHERE id = ?", UUID.class, postingId);

        assertThatThrownBy(() -> jdbc.update("UPDATE postings SET amount_minor = amount_minor * 2 WHERE id = ?",
                postingId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM postings WHERE id = ?", postingId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entries SET description = 'x' WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void zeroAmountPostingsAreRejectedByCheckConstraint() {
        UUID a = api.createWallet(TestIds.unique("w"), 0);
        UUID entry = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(entry, "USD");
            insertPosting(entry, a, 0);
        })).isInstanceOf(DataAccessException.class);
    }

    private void insertEntry(UUID id, String currency) {
        jdbc.update("INSERT INTO journal_entries (id, type, currency, created_at) VALUES (?, 'REVERSAL', ?, now())",
                id, currency);
    }

    private void insertPosting(UUID entry, UUID account, long amount) {
        jdbc.update("INSERT INTO postings (id, journal_entry_id, account_id, amount_minor, created_at) "
                + "VALUES (?, ?, ?, ?, now())", UUID.randomUUID(), entry, account, amount);
    }

    private long postingCount(UUID account) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM postings WHERE account_id = ?", Long.class, account);
        return count == null ? 0 : count;
    }
}
