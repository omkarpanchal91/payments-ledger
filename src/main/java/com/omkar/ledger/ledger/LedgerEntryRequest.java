package com.omkar.ledger.ledger;

import java.util.List;
import java.util.UUID;

/** What a business service asks {@link LedgerService#post} to record. */
public record LedgerEntryRequest(
        JournalEntryType type,
        UUID referenceId,
        String description,
        String currency,
        List<Leg> legs) {

    public LedgerEntryRequest {
        legs = List.copyOf(legs);
    }
}
