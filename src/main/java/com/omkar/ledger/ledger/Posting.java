package com.omkar.ledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One line of a journal entry: a signed amount on one account (debit = +, credit = -).
 *
 * <p>Foreign keys are plain UUID columns rather than {@code @ManyToOne} associations: postings are
 * write-once and read through SQL, so we avoid lazy-loading proxies entirely.
 */
@Entity
@Immutable
@Table(name = "postings")
public class Posting {

    @Id
    private UUID id;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Posting() {
    }

    public Posting(UUID id, UUID journalEntryId, UUID accountId, long amountMinor, Instant createdAt) {
        this.id = id;
        this.journalEntryId = journalEntryId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
