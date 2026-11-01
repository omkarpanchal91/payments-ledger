package com.omkar.ledger.refund;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "refunds")
public class Refund {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "fee_refund_minor", nullable = false)
    private long feeRefundMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Refund() {
    }

    public Refund(UUID id, UUID paymentId, long amountMinor, long feeRefundMinor, String currency,
                  UUID journalEntryId, Instant createdAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.amountMinor = amountMinor;
        this.feeRefundMinor = feeRefundMinor;
        this.currency = currency;
        this.status = RefundStatus.SUCCEEDED;
        this.journalEntryId = journalEntryId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getFeeRefundMinor() {
        return feeRefundMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
