package com.omkar.ledger.ledger.dto;

import com.omkar.ledger.ledger.JournalEntryType;
import java.time.Instant;
import java.util.UUID;

/**
 * One statement line.
 *
 * @param amountMinor signed, debit-positive amount exactly as stored
 * @param direction   DEBIT or CREDIT, derived from the sign
 */
public record PostingResponse(
        UUID id,
        String object,
        UUID journalEntryId,
        UUID accountId,
        long amountMinor,
        String direction,
        JournalEntryType entryType,
        UUID referenceId,
        String description,
        Instant createdAt) {
}
