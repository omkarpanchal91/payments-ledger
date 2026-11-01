package com.omkar.ledger.account;

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
 * JPA entity = Django model. Hibernate maps it to the {@code accounts} table created by Flyway.
 *
 * <h2>Two kinds of locking on the same row - and why</h2>
 * <ul>
 *   <li><b>Balances use pessimistic locks</b> ({@code SELECT ... FOR UPDATE}, taken by
 *   {@code LedgerService}). Balance updates are hot and contended: many transfers hit the same
 *   account at once. With optimistic locking most of them would fail on a version conflict and have
 *   to retry, which wastes work and can starve. A row lock makes concurrent writers queue up and
 *   each one sees the latest balance before checking "insufficient funds".</li>
 *   <li><b>Metadata uses optimistic locking</b> ({@link Version}). Status changes (freeze/unfreeze)
 *   are rare and rarely concurrent, so we don't want to hold locks for them. Hibernate adds
 *   {@code WHERE version = ?} to the UPDATE; if someone else changed the row in between, 0 rows
 *   match and the transaction is retried. Because balance updates also bump the version, a freeze
 *   can never silently overwrite a balance written by a concurrent transfer.</li>
 * </ul>
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountType type;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    /** Cached sum of all postings, debit-positive. Updated in the same transaction as the postings. */
    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA (Hibernate instantiates entities reflectively). */
    protected Account() {
    }

    public Account(UUID id, String code, AccountType type, String currency, boolean allowNegative, Instant now) {
        this.id = id;
        this.code = code;
        this.type = type;
        this.currency = currency;
        this.allowNegative = allowNegative;
        this.balanceMinor = 0;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Balance as a human reads it: positive means "this account holds money" for its type. */
    public long normalBalanceMinor() {
        return type.normalSide().toNormal(balanceMinor);
    }

    /** Only LedgerService calls this, together with inserting the matching posting. */
    public void applyPosting(long signedAmountMinor, Instant at) {
        this.balanceMinor = Math.addExact(balanceMinor, signedAmountMinor);
        this.updatedAt = at;
    }

    public void freeze(Instant at) {
        this.status = AccountStatus.FROZEN;
        this.updatedAt = at;
    }

    public void unfreeze(Instant at) {
        this.status = AccountStatus.ACTIVE;
        this.updatedAt = at;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public AccountStatus getStatus() {
        return status;
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
