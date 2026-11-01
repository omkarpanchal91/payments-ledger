package com.omkar.ledger.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String secret;

    /** PostgreSQL TEXT[]; Hibernate 6 maps Java arrays to SQL arrays natively. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "enabled_events", nullable = false, columnDefinition = "text[]")
    private String[] enabledEvents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookEndpointStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookEndpoint() {
    }

    public WebhookEndpoint(UUID id, String merchantId, String url, String secret, String[] enabledEvents,
                           Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.url = url;
        this.secret = secret;
        this.enabledEvents = enabledEvents.clone();
        this.status = WebhookEndpointStatus.ENABLED;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getUrl() {
        return url;
    }

    public String getSecret() {
        return secret;
    }

    public String[] getEnabledEvents() {
        return enabledEvents.clone();
    }

    public WebhookEndpointStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
