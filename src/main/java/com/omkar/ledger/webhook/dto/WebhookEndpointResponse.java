package com.omkar.ledger.webhook.dto;

import com.omkar.ledger.webhook.WebhookEndpoint;
import com.omkar.ledger.webhook.WebhookEndpointStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code secret} is only present in the creation response (null fields are omitted from JSON). */
public record WebhookEndpointResponse(
        UUID id,
        String object,
        String merchantId,
        String url,
        List<String> enabledEvents,
        WebhookEndpointStatus status,
        String secret,
        Instant createdAt) {

    public static WebhookEndpointResponse withSecret(WebhookEndpoint e) {
        return new WebhookEndpointResponse(e.getId(), "webhook_endpoint", e.getMerchantId(), e.getUrl(),
                List.of(e.getEnabledEvents()), e.getStatus(), e.getSecret(), e.getCreatedAt());
    }
}
