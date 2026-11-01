package com.omkar.ledger.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omkar.ledger.config.LedgerProperties;
import com.omkar.ledger.outbox.EventEnvelope;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer (group "webhook-dispatcher") on ledger.events - think of it as a Celery worker
 * subscribed to a queue, except Kafka keeps the log and we track our position (offset).
 *
 * <p>For each event: create delivery rows (deduped), commit, and only then let the container commit
 * the Kafka offset (ack-mode=record). If we crash before the DB commit, Kafka redelivers the record;
 * if we crash after it, the replay hits ON CONFLICT DO NOTHING. Either way: exactly one delivery
 * row per (event, endpoint). The first HTTP attempt happens right away (deliver-inline); failures
 * are picked up by {@link WebhookRetryWorker}.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final ObjectMapper objectMapper;
    private final WebhookDeliveryService deliveryService;
    private final boolean deliverInline;

    public WebhookDispatcher(ObjectMapper objectMapper, WebhookDeliveryService deliveryService,
                             LedgerProperties properties) {
        this.objectMapper = objectMapper;
        this.deliveryService = deliveryService;
        this.deliverInline = properties.webhooks().deliverInline();
    }

    @KafkaListener(
            id = "webhook-dispatcher",
            topics = "${ledger.kafka.events-topic}",
            groupId = "${ledger.webhooks.consumer-group}",
            autoStartup = "${ledger.webhooks.dispatcher-enabled}")
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event;
        try {
            event = objectMapper.readValue(record.value(), EventEnvelope.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // A poison message must not block the partition forever: log it and move on.
            log.error("Skipping malformed event at {}-{} offset {}: {}", record.topic(), record.partition(),
                    record.offset(), e.getMessage());
            return;
        }
        if (event.id() == null || event.type() == null) {
            log.error("Skipping event without id/type at {}-{} offset {}", record.topic(), record.partition(),
                    record.offset());
            return;
        }

        MDC.put("event_id", event.id().toString());
        try {
            List<UUID> created = deliveryService.createDeliveries(event);
            if (!created.isEmpty()) {
                log.info("Created {} webhook deliveries for {} {}", created.size(), event.type(), event.id());
            }
            if (deliverInline) {
                for (UUID deliveryId : created) {
                    try {
                        deliveryService.attemptNow(deliveryId);
                    } catch (RuntimeException e) {
                        // The row is committed; the retry worker will pick it up.
                        log.warn("Inline delivery {} failed: {}", deliveryId, e.toString());
                    }
                }
            }
        } finally {
            MDC.remove("event_id");
        }
    }
}
