package com.omkar.ledger.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.omkar.ledger.config.LedgerProperties;
import com.omkar.ledger.outbox.EventEnvelope;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a delivery that exhausted its retries to ledger.events.dlq, so an operator (or a
 * replay tool) can inspect and re-drive it. The DB row is the source of truth: dlq_published_at
 * is only set after Kafka acked, and the retry worker re-publishes rows where it is still null.
 */
@Component
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final WebhookDeliveryRepository deliveries;
    private final String topic;
    private final long timeoutMillis;

    public DeadLetterPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                               WebhookDeliveryRepository deliveries, LedgerProperties properties) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.deliveries = deliveries;
        this.topic = properties.kafka().dlqTopic();
        this.timeoutMillis = properties.outbox().sendTimeout().toMillis();
    }

    /** @return true if Kafka acknowledged the message */
    public boolean publish(WebhookDeliveryRepository.DeadDelivery dead, EventEnvelope event) {
        try {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("delivery_id", dead.id().toString());
            message.put("event_id", dead.eventId().toString());
            message.put("endpoint_id", dead.endpointId().toString());
            message.put("attempt_count", dead.attemptCount());
            if (dead.lastResponseCode() != null) {
                message.put("last_response_code", dead.lastResponseCode());
            }
            message.put("last_error", dead.lastError());
            message.set("event", objectMapper.valueToTree(event));
            kafka.send(topic, dead.eventId().toString(), objectMapper.writeValueAsString(message))
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            deliveries.markDlqPublished(dead.id());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Could not publish dead delivery {} to {}: {}", dead.id(), topic, e.toString());
            return false;
        }
    }
}
