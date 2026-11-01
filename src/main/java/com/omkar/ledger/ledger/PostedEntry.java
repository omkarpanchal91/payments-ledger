package com.omkar.ledger.ledger;

import java.util.List;

/** Result of a successful {@link LedgerService#post}: the entry and its postings. */
public record PostedEntry(JournalEntry entry, List<Posting> postings) {
}
