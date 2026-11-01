package com.omkar.ledger.webhook;

import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.Times;
import com.omkar.ledger.outbox.EventTypes;
import com.omkar.ledger.webhook.dto.CreateWebhookEndpointRequest;
import com.omkar.ledger.webhook.dto.WebhookEndpointResponse;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEndpointService {

    private final EntityManager entityManager;
    private final Clock clock;

    public WebhookEndpointService(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public WebhookEndpointResponse register(CreateWebhookEndpointRequest request) {
        validateUrl(request.url());
        var events = new LinkedHashSet<String>();
        for (String event : request.enabledEvents()) {
            if (!event.equals(EventTypes.WILDCARD) && !EventTypes.WEBHOOK_SUBSCRIBABLE.contains(event)) {
                throw ApiException.badRequest("unknown_event_type", "Unknown event type '" + event
                        + "'. Allowed: " + EventTypes.WEBHOOK_SUBSCRIBABLE + " or *.", "enabled_events");
            }
            events.add(event);
        }
        var endpoint = new WebhookEndpoint(UUID.randomUUID(), request.merchantId(), request.url(),
                WebhookSigner.generateSecret(), events.toArray(String[]::new), Times.now(clock));
        entityManager.persist(endpoint);
        return WebhookEndpointResponse.withSecret(endpoint);
    }

    private static void validateUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw invalidUrl();
            }
        } catch (URISyntaxException e) {
            throw invalidUrl();
        }
    }

    private static ApiException invalidUrl() {
        return ApiException.badRequest("invalid_url", "url must be an absolute http(s) URL.", "url");
    }
}
