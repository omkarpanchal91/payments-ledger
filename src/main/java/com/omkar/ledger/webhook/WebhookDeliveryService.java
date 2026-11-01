package com.omkar.ledger.webhook;

import com.omkar.ledger.common.LedgerMetrics;
import com.omkar.ledger.config.LedgerProperties;
import com.omkar.ledger.outbox.EventEnvelope;
import com.omkar.ledger.outbox.EventService;
import com.omkar.ledger.outbox.OutboxEventRow;
import com.omkar.ledger.outbox.OutboxRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Webhook delivery state machine:
 * <pre>
 *   PENDING --2xx--> SUCCEEDED
 *      |
 *      +--fail--> RETRYING --2xx--> SUCCEEDED
 *                    |  ^
 *                    +--+ fail, attempts left (exponential backoff + jitter)
 *                    |
 *                    +--fail, no attempts left--> DEAD --> ledger.events.dlq
 * </pre>
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final OutboxRepository outbox;
    private final EventService eventService;
    private final WebhookHttpSender sender;
    private final BackoffSchedule backoff;
    private final DeadLetterPublisher deadLetters;
    private final LedgerMetrics metrics;
    private final Duration lease;

    public WebhookDeliveryService(WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints,
                                  OutboxRepository outbox, EventService eventService, WebhookHttpSender sender,
                                  BackoffSchedule backoff, DeadLetterPublisher deadLetters, LedgerMetrics metrics,
                                  LedgerProperties properties) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.outbox = outbox;
        this.eventService = eventService;
        this.sender = sender;
        this.backoff = backoff;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
        this.lease = properties.webhooks().claimLease();
    }

    /**
     * Creates one delivery row per matching endpoint. ON CONFLICT DO NOTHING on (event_id, endpoint_id)
     * makes this idempotent: a replayed Kafka message creates nothing new.
     *
     * @return ids of deliveries created by this call (empty on a replay)
     */
    @Transactional
    public List<UUID> createDeliveries(EventEnvelope event) {
        String merchantId = event.data() == null ? null : event.data().path("merchant_id").asText(null);
        if (merchantId == null) {
            return List.of();
        }
        List<UUID> created = new ArrayList<>();
        for (WebhookEndpoint endpoint : endpoints.findEnabledForEvent(merchantId, event.type())) {
            deliveries.insertIfAbsent(event.id(), endpoint.getId()).ifPresent(created::add);
        }
        return created;
    }

    /** Attempt a specific delivery now, if it is due and nobody else holds its lease. */
    public void attemptNow(UUID deliveryId) {
        deliveries.claimOne(deliveryId, lease).ifPresent(this::deliver);
    }

    /** Used by the retry worker: claim and attempt up to {@code limit} due deliveries. */
    public int processDue(int limit) {
        List<ClaimedDelivery> claimed = deliveries.claimDue(limit, lease);
        claimed.forEach(this::deliver);
        return claimed.size();
    }

    public void republishDeadLetters(int limit) {
        for (UUID id : deliveries.findDeadNotInDlq(limit)) {
            deliveries.findDead(id).ifPresent(this::publishDeadLetter);
        }
    }

    void deliver(ClaimedDelivery delivery) {
        int attempt = delivery.attemptCount() + 1;
        Optional<WebhookEndpoint> endpoint = endpoints.findById(delivery.endpointId());
        Optional<OutboxEventRow> event = outbox.findById(delivery.eventId());
        if (endpoint.isEmpty() || event.isEmpty()) {
            deliveries.markDead(delivery.id(), attempt, null, "endpoint or event no longer exists");
            metrics.webhookDead();
            return;
        }

        String body = eventService.toJson(eventService.toEnvelope(event.get()));
        DeliveryResult result = sender.send(endpoint.get().getUrl(), endpoint.get().getSecret(),
                delivery.eventId(), attempt, body);

        if (result.successful()) {
            deliveries.markSucceeded(delivery.id(), attempt, result.statusCode());
            metrics.webhookSucceeded();
            log.info("Webhook delivery {} succeeded on attempt {}", delivery.id(), attempt);
            return;
        }

        metrics.webhookFailed();
        Optional<Duration> delay = backoff.delayAfterAttempt(attempt);
        if (delay.isPresent()) {
            deliveries.markRetrying(delivery.id(), attempt, result.statusCode(), result.error(), delay.get());
            log.info("Webhook delivery {} failed on attempt {} ({}); retrying in {}", delivery.id(), attempt,
                    result.error(), delay.get());
        } else {
            deliveries.markDead(delivery.id(), attempt, result.statusCode(), result.error());
            metrics.webhookDead();
            log.warn("Webhook delivery {} is DEAD after {} attempts ({})", delivery.id(), attempt, result.error());
            deliveries.findDead(delivery.id()).ifPresent(this::publishDeadLetter);
        }
    }

    private void publishDeadLetter(WebhookDeliveryRepository.DeadDelivery dead) {
        outbox.findById(dead.eventId()).ifPresent(row -> deadLetters.publish(dead, eventService.toEnvelope(row)));
    }
}
