package com.omkar.ledger.refund.dto;

import com.omkar.ledger.refund.Refund;
import com.omkar.ledger.refund.RefundStatus;
import java.time.Instant;
import java.util.UUID;

/** {@code merchant_id} is included so webhook routing can find the merchant's endpoints. */
public record RefundResponse(
        UUID id,
        String object,
        UUID paymentId,
        String merchantId,
        long amountMinor,
        long feeRefundMinor,
        String currency,
        RefundStatus status,
        UUID journalEntryId,
        Instant createdAt) {

    public static RefundResponse from(Refund r, String merchantId) {
        return new RefundResponse(r.getId(), "refund", r.getPaymentId(), merchantId, r.getAmountMinor(),
                r.getFeeRefundMinor(), r.getCurrency(), r.getStatus(), r.getJournalEntryId(), r.getCreatedAt());
    }
}
