package com.omkar.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** A small typed client for the ledger HTTP API, used by integration tests. Thread-safe. */
public class LedgerApi {

    public static final String ADMIN_KEY = "sk_test_admin_key";
    public static final String MERCHANT_KEY = "sk_test_merchant_key";

    private final TestRestTemplate rest;
    private final ObjectMapper mapper;

    public LedgerApi(TestRestTemplate rest, ObjectMapper mapper) {
        this.rest = rest;
        this.mapper = mapper;
    }

    // ---- raw HTTP ----

    public ApiResponse post(String path, Object body) {
        return post(path, body, UUID.randomUUID().toString(), ADMIN_KEY);
    }

    public ApiResponse post(String path, Object body, String idempotencyKey) {
        return post(path, body, idempotencyKey, ADMIN_KEY);
    }

    public ApiResponse post(String path, Object body, String idempotencyKey, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set("X-Api-Key", apiKey);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        String json = body == null ? null : body instanceof String s ? s : write(body);
        return exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers));
    }

    public ApiResponse get(String path) {
        return get(path, ADMIN_KEY);
    }

    public ApiResponse get(String path, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set("X-Api-Key", apiKey);
        }
        return exchange(path, HttpMethod.GET, new HttpEntity<>(headers));
    }

    private ApiResponse exchange(String path, HttpMethod method, HttpEntity<String> entity) {
        ResponseEntity<String> response = rest.exchange(path, method, entity, String.class);
        String body = response.getBody();
        return new ApiResponse(response.getStatusCode().value(), body, response.getHeaders(), parse(body));
    }

    // ---- domain helpers ----

    public static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    public ApiResponse createAccount(String code, String type, String currency, boolean allowNegative) {
        ApiResponse r = post("/v1/accounts", body("code", code, "type", type, "currency", currency,
                "allow_negative", allowNegative));
        assertThat(r.status()).as("create account %s: %s", code, r).isEqualTo(201);
        return r;
    }

    public UUID createWallet(String code, long fundedAmount) {
        UUID id = createAccount(code, "LIABILITY", "USD", false).id();
        if (fundedAmount > 0) {
            fund(id, fundedAmount, "USD");
        }
        return id;
    }

    public ApiResponse fund(UUID accountId, long amount, String currency) {
        ApiResponse r = post("/v1/fundings", body("account_id", accountId, "amount_minor", amount,
                "currency", currency));
        assertThat(r.status()).as("funding: %s", r).isEqualTo(201);
        return r;
    }

    /** Creates customer_funds:&lt;customerId&gt; funded with {@code amount} USD. */
    public UUID createCustomer(String customerId, long amount) {
        return createWallet("customer_funds:" + customerId, amount);
    }

    public UUID createMerchant(String merchantId) {
        return createAccount("merchant_balance:" + merchantId, "LIABILITY", "USD", false).id();
    }

    public ApiResponse pay(String customerId, String merchantId, long amount) {
        return post("/v1/payments", body("customer_id", customerId, "merchant_id", merchantId,
                "amount_minor", amount, "currency", "USD"));
    }

    public ApiResponse transfer(UUID from, UUID to, long amount) {
        return post("/v1/transfers", body("from_account_id", from, "to_account_id", to,
                "amount_minor", amount, "currency", "USD"));
    }

    public long balance(UUID accountId) {
        ApiResponse r = get("/v1/accounts/" + accountId);
        assertThat(r.status()).as("get account: %s", r).isEqualTo(200);
        return r.getLong("balance_minor");
    }

    public long balanceByCode(org.springframework.jdbc.core.JdbcTemplate jdbc, String code) {
        UUID id = jdbc.queryForObject("SELECT id FROM accounts WHERE code = ?", UUID.class, code);
        return balance(id);
    }

    private String write(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return MissingNode.getInstance();
        }
    }
}
