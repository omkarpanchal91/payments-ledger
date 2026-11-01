package com.omkar.ledger.webhook;

import com.omkar.ledger.config.LedgerProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Performs one signed HTTP POST of an event to a merchant endpoint (JDK HttpClient ~ requests.post). */
@Component
public class WebhookHttpSender {

    public static final String EVENT_ID_HEADER = "Ledger-Event-Id";
    public static final String ATTEMPT_HEADER = "Ledger-Delivery-Attempt";

    private final HttpClient httpClient;
    private final WebhookSigner signer;
    private final Clock clock;
    private final Duration timeout;

    public WebhookHttpSender(HttpClient webhookHttpClient, WebhookSigner signer, Clock clock,
                             LedgerProperties properties) {
        this.httpClient = webhookHttpClient;
        this.signer = signer;
        this.clock = clock;
        this.timeout = properties.webhooks().requestTimeout();
    }

    public DeliveryResult send(String url, String secret, UUID eventId, int attempt, String body) {
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "payments-ledger-webhooks/1.0")
                    .header(WebhookSigner.HEADER, signer.signatureHeader(secret, timestamp, body))
                    .header(EVENT_ID_HEADER, eventId.toString())
                    .header(ATTEMPT_HEADER, Integer.toString(attempt))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            return DeliveryResult.failure(null, "invalid_url: " + e.getMessage());
        }
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            return code >= 200 && code < 300
                    ? DeliveryResult.success(code)
                    : DeliveryResult.failure(code, "HTTP " + code);
        } catch (HttpTimeoutException e) {
            return DeliveryResult.failure(null, "timeout after " + timeout.toMillis() + "ms");
        } catch (IOException e) {
            return DeliveryResult.failure(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure(null, "interrupted");
        }
    }
}
