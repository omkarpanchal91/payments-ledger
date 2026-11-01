package com.omkar.ledger.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    /** Created at startup by Spring's KafkaAdmin if it does not exist yet. */
    @Bean
    public NewTopic ledgerEventsTopic(LedgerProperties properties) {
        var kafka = properties.kafka();
        return TopicBuilder.name(kafka.eventsTopic())
                .partitions(kafka.partitions())
                .replicas(kafka.replicationFactor())
                .build();
    }

    @Bean
    public NewTopic ledgerEventsDeadLetterTopic(LedgerProperties properties) {
        var kafka = properties.kafka();
        return TopicBuilder.name(kafka.dlqTopic())
                .partitions(kafka.partitions())
                .replicas(kafka.replicationFactor())
                .build();
    }

    /**
     * If the listener throws (e.g. PostgreSQL is briefly unavailable), retry the same record with
     * exponential backoff instead of skipping it. The offset is not committed until the record is
     * processed, so no event is lost. Malformed messages are handled inside the listener and never
     * reach this handler, so they cannot block a partition.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        var backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        return new DefaultErrorHandler(backOff);
    }
}
