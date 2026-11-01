package com.omkar.ledger.funding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateFundingRequest(
        @NotNull UUID accountId,
        @NotNull @Positive Long amountMinor,
        @NotBlank String currency,
        @Size(max = 500) String description) {
}
