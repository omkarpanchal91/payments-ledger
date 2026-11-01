package com.omkar.ledger.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer-to-merchant payment. {@code refundedMinor} is metadata updated by refunds, protected
 * twice: refunds lock the row with SELECT ... FOR UPDATE (so two parallel refunds are serialized and
 * the second one sees the first one's refundedMinor), and {@link Version} catches any writer that
 * forgot to lock.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "fee_minor", nullable = false, updatable = false)
    private long feeMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    private Payment(UUID id, String merchantId, String customerId, long amountMinor, long feeMinor, String currency,
                    PaymentStatus status, String failureCode, UUID journalEntryId, Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.customerId = customerId;
        this.amountMinor = amountMinor;
        this.feeMinor = feeMinor;
        this.currency = currency;
        this.status = status;
        this.failureCode = failureCode;
        this.refundedMinor = 0;
        this.journalEntryId = journalEntryId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Payment succeeded(UUID id, String merchantId, String customerId, long amountMinor, long feeMinor,
                                    String currency, UUID journalEntryId, Instant now) {
        return new Payment(id, merchantId, customerId, amountMinor, feeMinor, currency, PaymentStatus.SUCCEEDED,
                null, journalEntryId, now);
    }

    /** No money moved, so no fee was charged and there is no journal entry. */
    public static Payment failed(UUID id, String merchantId, String customerId, long amountMinor, String currency,
                                 String failureCode, Instant now) {
        return new Payment(id, merchantId, customerId, amountMinor, 0, currency, PaymentStatus.FAILED,
                failureCode, null, now);
    }

    public long refundableMinor() {
        return amountMinor - refundedMinor;
    }

    public void recordRefund(long refundMinor, Instant at) {
        if (refundMinor <= 0 || refundMinor > refundableMinor()) {
            throw new IllegalStateException("Refund of " + refundMinor + " exceeds refundable " + refundableMinor());
        }
        this.refundedMinor = Math.addExact(refundedMinor, refundMinor);
        this.updatedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public long getRefundedMinor() {
        return refundedMinor;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
