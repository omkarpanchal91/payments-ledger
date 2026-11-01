package com.omkar.ledger.transfer.dto;

import java.time.Instant;
import java.util.UUID;

/** A transfer is identified by its journal entry id. */
public record TransferResponse(
        UUID id,
        String object,
        UUID fromAccountId,
        UUID toAccountId,
        long amountMinor,
        String currency,
        String description,
        Instant createdAt) {
}
