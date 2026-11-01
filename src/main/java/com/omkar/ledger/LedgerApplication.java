package com.omkar.ledger;

import com.omkar.ledger.config.LedgerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. {@code @SpringBootApplication} turns on component scanning for every class under
 * {@code com.omkar.ledger} (similar to Django discovering the apps in INSTALLED_APPS) and enables
 * auto-configuration (DataSource, JPA, Kafka, Jackson, ... are wired from application.yml).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
