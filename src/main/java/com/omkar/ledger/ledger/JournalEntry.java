package com.omkar.ledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One business event that moves money, made of two or more {@link Posting}s that sum to zero.
 * {@code @Immutable} tells Hibernate never to issue UPDATEs for it; the database additionally
 * rejects UPDATE/DELETE with a trigger.
 */
@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JournalEntryType type;

    @Column(name = "reference_id")
    private UUID referenceId;

    private String description;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JournalEntry() {
    }

    public JournalEntry(UUID id, JournalEntryType type, UUID referenceId, String description, String currency,
                        Instant createdAt) {
        this.id = id;
        this.type = type;
        this.referenceId = referenceId;
        this.description = description;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public JournalEntryType getType() {
        return type;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getDescription() {
        return description;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
