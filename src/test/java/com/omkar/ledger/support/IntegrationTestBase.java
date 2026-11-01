package com.omkar.ledger.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * Base class for integration tests: real PostgreSQL 16 and real Kafka (KRaft) in Docker.
 *
 * <p>The containers are started once per JVM (static "singleton container" pattern) and every test
 * class shares one Spring context, because they all use the same configuration (Spring caches the
 * context). That keeps the suite fast: Docker and Spring start once, not once per class.
 *
 * <p>Tests isolate themselves with unique ids (accounts, merchants, idempotency keys) instead of
 * truncating tables, so they can also assert global invariants such as reconciliation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    protected LedgerApi api;

    @BeforeEach
    void setUpApi() {
        api = new LedgerApi(restTemplate, objectMapper);
    }

    protected static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
