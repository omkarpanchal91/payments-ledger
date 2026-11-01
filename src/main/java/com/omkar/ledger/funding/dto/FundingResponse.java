package com.omkar.ledger.funding.dto;

import java.time.Instant;
import java.util.UUID;

public record FundingResponse(
        UUID id,
        String object,
        UUID accountId,
        UUID settlementAccountId,
        long amountMinor,
        String currency,
        String description,
        Instant createdAt) {
}
