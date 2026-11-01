package com.omkar.ledger.webhook;

import static com.omkar.ledger.support.LedgerApi.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.omkar.ledger.outbox.EventEnvelope;
import com.omkar.ledger.outbox.EventService;
import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.KafkaTestConsumer;
import com.omkar.ledger.support.TestIds;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * End to end: payment -> outbox -> relay -> Kafka -> dispatcher -> signed HTTP POST to a fake
 * merchant server (MockWebServer), including retries, DEAD + DLQ, and replay dedupe.
 */
class WebhookIntegrationTest extends IntegrationTestBase {

    private MockWebServer merchantServer;
    private final AtomicInteger requestsSeen = new AtomicInteger();

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EventService eventService;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @BeforeEach
    void startMerchantServer() throws Exception {
        merchantServer = new MockWebServer();
        merchantServer.start();
    }

    @AfterEach
    void stopMerchantServer() throws Exception {
        merchantServer.shutdown();
    }

    /** Merchant server answering with the given status codes in order (the last one repeats). */
    private void respondWith(int... codes) {
        merchantServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int n = requestsSeen.getAndIncrement();
                int code = codes[Math.min(n, codes.length - 1)];
                return new MockResponse().setResponseCode(code);
            }
        });
    }

    private record Merchant(String merchantId, String customerId, String secret, UUID endpointId) {
    }

    private Merchant merchantWithEndpoint(List<String> events) {
        String merchant = TestIds.unique("m");
        String customer = TestIds.unique("c");
        api.createMerchant(merchant);
        api.createCustomer(customer, 100_000);
        ApiResponse endpoint = api.post("/v1/webhook_endpoints", body("merchant_id", merchant,
                "url", merchantServer.url("/hooks").toString(), "enabled_events", events));
        assertThat(endpoint.status()).as("register endpoint: %s", endpoint).isEqualTo(201);
        return new Merchant(merchant, customer, endpoint.getText("secret"), endpoint.id());
    }

    private UUID eventIdFor(UUID aggregateId, String type) {
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                UUID.class, aggregateId, type);
    }

    private Map<String, Object> delivery(UUID eventId, UUID endpointId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, attempt_count, last_response_code, dlq_published_at FROM webhook_deliveries "
                        + "WHERE event_id = ? AND endpoint_id = ?", eventId, endpointId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    @Test
    void registrationReturnsTheSecretOnce() {
        String merchant = TestIds.unique("m");
        ApiResponse r = api.post("/v1/webhook_endpoints", body("merchant_id", merchant,
                "url", "https://merchant.example/hooks", "enabled_events", List.of("payment.succeeded", "*")));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.getText("object")).isEqualTo("webhook_endpoint");
        assertThat(r.getText("status")).isEqualTo("ENABLED");
        assertThat(Base64.getDecoder().decode(r.getText("secret"))).hasSize(32);
        assertThat(r.json().path("enabled_events")).hasSize(2);
    }

    @Test
    void registrationValidatesUrlAndEventTypes() {
        String merchant = TestIds.unique("m");
        ApiResponse badUrl = api.post("/v1/webhook_endpoints", body("merchant_id", merchant,
                "url", "ftp://merchant.example", "enabled_events", List.of("payment.succeeded")));
        assertThat(badUrl.status()).isEqualTo(400);
        assertThat(badUrl.errorCode()).isEqualTo("invalid_url");

        ApiResponse badEvent = api.post("/v1/webhook_endpoints", body("merchant_id", merchant,
                "url", "https://merchant.example", "enabled_events", List.of("payment.exploded")));
        assertThat(badEvent.status()).isEqualTo(400);
        assertThat(badEvent.errorCode()).isEqualTo("unknown_event_type");

        ApiResponse noEvents = api.post("/v1/webhook_endpoints", body("merchant_id", merchant,
                "url", "https://merchant.example", "enabled_events", List.of()));
        assertThat(noEvents.status()).isEqualTo(400);
    }

    @Test
    void deliversACorrectlySignedWebhook() throws Exception {
        respondWith(200);
        Merchant m = merchantWithEndpoint(List.of("payment.succeeded"));

        UUID paymentId = api.pay(m.customerId(), m.merchantId(), 1_000).id();
        UUID eventId = eventIdFor(paymentId, "payment.succeeded");

        RecordedRequest request = merchantServer.takeRequest(30, TimeUnit.SECONDS);
        assertThat(request).as("webhook request").isNotNull();
        String payload = request.getBody().readUtf8();

        new WebhookSignatureVerifier().verify(payload, request.getHeader("Ledger-Signature"), m.secret());
        assertThat(request.getHeader("Ledger-Event-Id")).isEqualTo(eventId.toString());
        assertThat(request.getHeader("Ledger-Delivery-Attempt")).isEqualTo("1");

        JsonNode json = objectMapper.readTree(payload);
        assertThat(json.path("id").asText()).isEqualTo(eventId.toString());
        assertThat(json.path("type").asText()).isEqualTo("payment.succeeded");
        assertThat(json.path("data").path("id").asText()).isEqualTo(paymentId.toString());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> d = delivery(eventId, m.endpointId());
            assertThat(d.get("status")).isEqualTo("SUCCEEDED");
            assertThat(d.get("attempt_count")).isEqualTo(1);
            assertThat(d.get("last_response_code")).isEqualTo(200);
        });
    }

    @Test
    void signatureFailsWithTheWrongSecret() throws Exception {
        respondWith(200);
        Merchant m = merchantWithEndpoint(List.of("*"));
        api.pay(m.customerId(), m.merchantId(), 500);

        RecordedRequest request = merchantServer.takeRequest(30, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        String payload = request.getBody().readUtf8();
        assertThat(new WebhookSignatureVerifier().isValid(payload, request.getHeader("Ledger-Signature"),
                WebhookSigner.generateSecret())).isFalse();
    }

    @Test
    void failedDeliveriesAreRetriedUntilTheySucceed() throws Exception {
        respondWith(500, 503, 200);
        Merchant m = merchantWithEndpoint(List.of("payment.succeeded"));
        UUID paymentId = api.pay(m.customerId(), m.merchantId(), 1_000).id();
        UUID eventId = eventIdFor(paymentId, "payment.succeeded");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> d = delivery(eventId, m.endpointId());
            assertThat(d.get("status")).isEqualTo("SUCCEEDED");
            assertThat(d.get("attempt_count")).isEqualTo(3);
        });
        assertThat(merchantServer.getRequestCount()).isEqualTo(3);
    }

    @Test
    void deliveryIsDeadAfterMaxAttemptsAndGoesToTheDlq() {
        respondWith(500);
        Merchant m = merchantWithEndpoint(List.of("payment.succeeded"));
        UUID paymentId = api.pay(m.customerId(), m.merchantId(), 1_000).id();
        UUID eventId = eventIdFor(paymentId, "payment.succeeded");

        // test schedule: 200ms, 200ms, 200ms -> 4 attempts in total
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> d = delivery(eventId, m.endpointId());
            assertThat(d.get("status")).isEqualTo("DEAD");
            assertThat(d.get("attempt_count")).isEqualTo(4);
            assertThat(d.get("last_response_code")).isEqualTo(500);
            assertThat(d.get("dlq_published_at")).isNotNull();
        });
        assertThat(merchantServer.getRequestCount()).isEqualTo(4);

        var dlqRecord = KafkaTestConsumer.pollFor(kafkaBootstrapServers(), "ledger.events.dlq",
                r -> r.value().contains("\"event_id\":\"" + eventId + "\""), Duration.ofSeconds(30));
        assertThat(dlqRecord).as("DLQ message for event %s", eventId).isPresent();
        assertThat(dlqRecord.get().value()).contains("\"attempt_count\":4");
    }

    @Test
    void replayedKafkaMessageDoesNotCreateADuplicateDelivery() throws Exception {
        respondWith(200);
        Merchant m = merchantWithEndpoint(List.of("payment.succeeded"));
        UUID paymentId = api.pay(m.customerId(), m.merchantId(), 1_000).id();
        UUID eventId = eventIdFor(paymentId, "payment.succeeded");

        await().atMost(Duration.ofSeconds(30))
                .until(() -> "SUCCEEDED".equals(delivery(eventId, m.endpointId()).get("status")));

        // Replay the exact same event onto the topic, as an outbox re-publish after a crash would.
        EventEnvelope envelope = eventService.get(eventId);
        kafkaTemplate.send("ledger.events", paymentId.toString(), eventService.toJson(envelope))
                .get(10, TimeUnit.SECONDS);

        // The dispatcher's dedupe is deterministic: a second createDeliveries() creates nothing.
        assertThat(deliveryService.createDeliveries(envelope)).isEmpty();

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(merchantServer.getRequestCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE event_id = ?",
                    Long.class, eventId)).isEqualTo(1);
        });
    }

    @Test
    void endpointsOnlyReceiveSubscribedEventTypes() throws Exception {
        respondWith(200);
        Merchant m = merchantWithEndpoint(List.of("refund.created"));
        UUID paymentId = api.pay(m.customerId(), m.merchantId(), 1_000).id();
        ApiResponse refund = api.post("/v1/payments/" + paymentId + "/refunds", body("amount_minor", 100));
        assertThat(refund.status()).isEqualTo(201);

        RecordedRequest request = merchantServer.takeRequest(30, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        JsonNode json = objectMapper.readTree(request.getBody().readUtf8());
        assertThat(json.path("type").asText()).isEqualTo("refund.created");
        assertThat(json.path("data").path("payment_id").asText()).isEqualTo(paymentId.toString());

        // and nothing for payment.succeeded
        assertThat(merchantServer.takeRequest(2, TimeUnit.SECONDS)).isNull();
    }
}
