package com.omkar.ledger.refund.dto;

import jakarta.validation.constraints.Positive;

/** {@code amount_minor} omitted (or no body at all) means "refund everything that is left". */
public record CreateRefundRequest(@Positive Long amountMinor) {

    public static final CreateRefundRequest FULL = new CreateRefundRequest(null);
}
