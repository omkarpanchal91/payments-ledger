package com.omkar.ledger.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreatePaymentRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "must be 1-64 chars of letters, digits, _ -")
        String customerId,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "must be 1-64 chars of letters, digits, _ -")
        String merchantId,
        @NotNull @Positive Long amountMinor,
        @NotBlank String currency) {
}
