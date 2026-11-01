package com.omkar.ledger.account.dto;

import com.omkar.ledger.account.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Records are immutable data classes (like a frozen dataclass / pydantic model). The Jakarta
 * Validation annotations are checked by {@code @Valid} in the controller before our code runs.
 */
public record CreateAccountRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_.:-]{1,128}$", message = "must be 1-128 chars of letters, digits, _ . : -")
        String code,
        @NotNull AccountType type,
        @NotBlank String currency,
        Boolean allowNegative) {
}
