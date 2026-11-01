package com.omkar.ledger.payment.dto;

import com.omkar.ledger.payment.Payment;
import com.omkar.ledger.payment.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String object,
        String merchantId,
        String customerId,
        long amountMinor,
        long feeMinor,
        long netMinor,
        String currency,
        PaymentStatus status,
        String failureCode,
        long refundedMinor,
        UUID journalEntryId,
        Instant createdAt) {

    public static PaymentResponse from(Payment p) {
        long net = p.getStatus() == PaymentStatus.SUCCEEDED ? p.getAmountMinor() - p.getFeeMinor() : 0;
        return new PaymentResponse(p.getId(), "payment", p.getMerchantId(), p.getCustomerId(), p.getAmountMinor(),
                p.getFeeMinor(), net, p.getCurrency(), p.getStatus(), p.getFailureCode(), p.getRefundedMinor(),
                p.getJournalEntryId(), p.getCreatedAt());
    }
}
