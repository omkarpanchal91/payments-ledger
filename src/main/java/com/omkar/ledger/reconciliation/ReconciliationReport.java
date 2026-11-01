package com.omkar.ledger.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param balanced true when every check passed
 */
public record ReconciliationReport(
        boolean balanced,
        Instant checkedAt,
        long accountsChecked,
        long journalEntriesChecked,
        List<AccountMismatch> accountMismatches,
        List<UnbalancedEntry> unbalancedEntries,
        List<CurrencyImbalance> currencyImbalances) {

    /** Cached balance on the account row differs from the sum of its postings. */
    public record AccountMismatch(UUID accountId, String code, long cachedBalanceMinor, long postingsSumMinor) {
    }

    /** A journal entry whose postings don't sum to zero (should be impossible due to the DB trigger). */
    public record UnbalancedEntry(UUID journalEntryId, long sumMinor) {
    }

    /** All postings in a currency must sum to zero across the whole ledger. */
    public record CurrencyImbalance(String currency, long sumMinor) {
    }
}
