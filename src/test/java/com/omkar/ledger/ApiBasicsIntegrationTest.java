package com.omkar.ledger;

import static com.omkar.ledger.support.LedgerApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.LedgerApi;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

class ApiBasicsIntegrationTest extends IntegrationTestBase {

    @Test
    void healthEndpointIsUpWithoutApiKey() {
        ApiResponse r = api.get("/actuator/health", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.getText("status")).isEqualTo("UP");
    }

    @Test
    void openApiDocsAreServed() {
        ApiResponse r = api.get("/v3/api-docs", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("paths").has("/v1/payments")).isTrue();
    }

    @Test
    void missingApiKeyIs401() {
        ApiResponse r = api.get("/v1/accounts/" + UUID.randomUUID(), null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.errorType()).isEqualTo("authentication_error");
        assertThat(r.errorCode()).isEqualTo("invalid_api_key");
    }

    @Test
    void wrongApiKeyIs401() {
        ApiResponse r = api.get("/v1/accounts/" + UUID.randomUUID(), "sk_wrong");
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void adminEndpointRequiresAdminKey() {
        ApiResponse r = api.get("/v1/admin/reconciliation", LedgerApi.MERCHANT_KEY);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.errorCode()).isEqualTo("admin_required");
    }

    @Test
    void requestIdIsEchoedInHeaderAndErrorBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", LedgerApi.ADMIN_KEY);
        headers.set("X-Request-Id", "test-req-123");
        ResponseEntity<String> response = restTemplate.exchange("/v1/accounts/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("test-req-123");
        assertThat(response.getBody()).contains("\"request_id\":\"test-req-123\"");
    }

    @Test
    void requestIdIsGeneratedWhenMissing() {
        ApiResponse r = api.get("/actuator/health", null);
        assertThat(r.headers().getFirst("X-Request-Id")).startsWith("req_");
    }

    @Test
    void unknownRouteIs404InStandardFormat() {
        ApiResponse r = api.get("/v1/does-not-exist");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.errorCode()).isEqualTo("resource_missing");
    }

    @Test
    void postWithoutIdempotencyKeyIs400() {
        ApiResponse r = api.post("/v1/accounts", body("code", "x", "type", "LIABILITY", "currency", "USD"), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("missing_header");
        assertThat(r.json().path("error").path("param").asText()).isEqualTo("Idempotency-Key");
    }

    @Test
    void tooLongIdempotencyKeyIs400() {
        ApiResponse r = api.post("/v1/accounts", body("code", "x_long_key", "type", "LIABILITY", "currency", "USD"),
                "k".repeat(256));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("idempotency_key_invalid");
    }

    @Test
    void malformedJsonIs400() {
        ApiResponse r = api.post("/v1/accounts", "{not json");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("invalid_json");
    }

    @Test
    void unknownFieldsAreRejected() {
        ApiResponse r = api.post("/v1/accounts",
                body("code", "x_unknown", "type", "LIABILITY", "currency", "USD", "colour", "blue"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("invalid_json");
    }

    @Test
    void validationErrorsNameTheSnakeCaseField() {
        ApiResponse r = api.post("/v1/transfers", body("from_account_id", UUID.randomUUID(),
                "to_account_id", UUID.randomUUID(), "currency", "USD"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("parameter_missing");
        assertThat(r.json().path("error").path("param").asText()).isEqualTo("amount_minor");
    }
}
