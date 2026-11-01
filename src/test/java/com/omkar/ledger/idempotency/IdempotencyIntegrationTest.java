package com.omkar.ledger.idempotency;

import static com.omkar.ledger.support.LedgerApi.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.LedgerApi;
import com.omkar.ledger.support.TestIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class IdempotencyIntegrationTest extends IntegrationTestBase {

    private static final String ADMIN_CLIENT = "test-admin";

    @Autowired
    private RequestHasher hasher;

    @Autowired
    private IdempotentRequestExecutor executor;

    private Map<String, Object> paymentBody(String customer, String merchant, long amount) {
        return body("customer_id", customer, "merchant_id", merchant, "amount_minor", amount, "currency", "USD");
    }

    private long paymentCount(String merchant) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id = ?", Long.class, merchant);
        return n == null ? 0 : n;
    }

    @Test
    void sameKeyAndBodyReturnsTheIdenticalResponseAndChargesOnce() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        UUID customerAccount = api.createCustomer(customer, 10_000);
        api.createMerchant(merchant);
        String key = UUID.randomUUID().toString();

        ApiResponse first = api.post("/v1/payments", paymentBody(customer, merchant, 1_000), key);
        ApiResponse second = api.post("/v1/payments", paymentBody(customer, merchant, 1_000), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(first.headers().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(paymentCount(merchant)).isEqualTo(1);
        assertThat(api.balance(customerAccount)).isEqualTo(9_000);
    }

    @Test
    void keyOrderInTheBodyDoesNotMatter() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        api.createCustomer(customer, 10_000);
        api.createMerchant(merchant);
        String key = UUID.randomUUID().toString();

        ApiResponse first = api.post("/v1/payments", String.format(
                "{\"customer_id\":\"%s\",\"merchant_id\":\"%s\",\"amount_minor\":500,\"currency\":\"USD\"}",
                customer, merchant), key);
        ApiResponse second = api.post("/v1/payments", String.format(
                "{ \"currency\": \"USD\", \"amount_minor\": 500, \"merchant_id\": \"%s\", \"customer_id\": \"%s\" }",
                merchant, customer), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(paymentCount(merchant)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIs422() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        api.createCustomer(customer, 10_000);
        api.createMerchant(merchant);
        String key = UUID.randomUUID().toString();

        assertThat(api.post("/v1/payments", paymentBody(customer, merchant, 1_000), key).status()).isEqualTo(201);
        ApiResponse reused = api.post("/v1/payments", paymentBody(customer, merchant, 2_000), key);

        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.errorType()).isEqualTo("idempotency_error");
        assertThat(reused.errorCode()).isEqualTo("idempotency_key_reused");
        assertThat(paymentCount(merchant)).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerEndpointAndPerClient() {
        UUID a = api.createWallet(TestIds.unique("w"), 10_000);
        UUID b = api.createWallet(TestIds.unique("w"), 0);
        String key = UUID.randomUUID().toString();

        ApiResponse transfer = api.post("/v1/transfers", body("from_account_id", a, "to_account_id", b,
                "amount_minor", 100, "currency", "USD"), key);
        ApiResponse funding = api.post("/v1/fundings", body("account_id", b, "amount_minor", 100,
                "currency", "USD"), key);
        ApiResponse otherClient = api.post("/v1/fundings", body("account_id", b, "amount_minor", 100,
                "currency", "USD"), key, LedgerApi.MERCHANT_KEY);

        assertThat(transfer.status()).isEqualTo(201);
        assertThat(funding.status()).isEqualTo(201);
        assertThat(otherClient.status()).isEqualTo(201);
        assertThat(otherClient.id()).isNotEqualTo(funding.id());
        assertThat(api.balance(b)).isEqualTo(300);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOnePayment() throws Exception {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        UUID customerAccount = api.createCustomer(customer, 100_000);
        api.createMerchant(merchant);
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = paymentBody(customer, merchant, 1_000);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return api.post("/v1/payments", request, key);
            }));
        }
        start.countDown();
        List<ApiResponse> responses = new ArrayList<>();
        for (Future<ApiResponse> f : futures) {
            responses.add(f.get(1, TimeUnit.MINUTES));
        }
        pool.shutdown();

        List<ApiResponse> created = responses.stream().filter(r -> r.status() == 201).toList();
        List<ApiResponse> inProgress = responses.stream().filter(r -> r.status() == 409).toList();
        assertThat(created.size() + inProgress.size()).as("statuses: %s", responses).isEqualTo(threads);
        assertThat(created).isNotEmpty();
        assertThat(created).extracting(ApiResponse::body).containsOnly(created.get(0).body());
        inProgress.forEach(r -> assertThat(r.errorCode()).isEqualTo("request_in_progress"));

        assertThat(paymentCount(merchant)).isEqualTo(1);
        assertThat(api.balance(customerAccount)).isEqualTo(99_000);
    }

    @Test
    void businessErrorIsStoredAndReplayedEvenAfterTheStateChanges() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        UUID customerAccount = api.createCustomer(customer, 100);
        api.createMerchant(merchant);
        String key = UUID.randomUUID().toString();

        ApiResponse first = api.post("/v1/payments", paymentBody(customer, merchant, 5_000), key);
        assertThat(first.status()).isEqualTo(422);
        assertThat(first.errorCode()).isEqualTo("insufficient_funds");

        api.fund(customerAccount, 10_000, "USD");
        ApiResponse retry = api.post("/v1/payments", paymentBody(customer, merchant, 5_000), key);

        assertThat(retry.status()).isEqualTo(422);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(api.balance(customerAccount)).isEqualTo(10_100);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE merchant_id = ?", String.class, merchant))
                .isEqualTo("FAILED");
    }

    @Test
    void requestWithActiveLockIsRejectedWith409() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        api.createCustomer(customer, 10_000);
        api.createMerchant(merchant);
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = paymentBody(customer, merchant, 1_000);

        insertKey(key, hasher.hash(request), "now() + interval '1 minute'");

        ApiResponse r = api.post("/v1/payments", request, key);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.errorCode()).isEqualTo("request_in_progress");
        assertThat(paymentCount(merchant)).isZero();
    }

    @Test
    void expiredLockOfACrashedRequestIsTakenOver() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        api.createCustomer(customer, 10_000);
        api.createMerchant(merchant);
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = paymentBody(customer, merchant, 1_000);

        // Simulate a request that claimed the key and then crashed: IN_PROGRESS with an expired lock.
        UUID rowId = insertKey(key, hasher.hash(request), "now() - interval '1 minute'");

        ApiResponse r = api.post("/v1/payments", request, key);
        assertThat(r.status()).isEqualTo(201);
        assertThat(paymentCount(merchant)).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, status FROM idempotency_keys WHERE client_id = ? AND idempotency_key = ?",
                ADMIN_CLIENT, key);
        assertThat(row.get("id")).isEqualTo(rowId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");

        ApiResponse replay = api.post("/v1/payments", request, key);
        assertThat(replay.body()).isEqualTo(r.body());
    }

    @Test
    void unexpectedErrorReleasesTheKeySoTheClientCanRetry() {
        String key = UUID.randomUUID().toString();
        var request = new IdempotentRequest("client-x", key, "POST /v1/test", Map.of("a", 1));

        assertThatThrownBy(() -> executor.execute(request, HttpStatus.CREATED, () -> {
            throw new IllegalStateException("database exploded");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?",
                Long.class, key)).isZero();

        ResponseEntity<String> retried = executor.execute(request, HttpStatus.CREATED,
                () -> Outcome.success(Map.of("ok", true)));
        assertThat(retried.getStatusCode().value()).isEqualTo(201);
        assertThat(retried.getBody()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void conflictErrorsAreNotStored() {
        String key = UUID.randomUUID().toString();
        var request = new IdempotentRequest("client-y", key, "POST /v1/test", Map.of("b", 2));

        assertThatThrownBy(() -> executor.execute(request, HttpStatus.CREATED, () -> {
            throw com.omkar.ledger.common.ApiException.conflict("concurrent_modification", "busy");
        })).hasMessage("busy");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?",
                Long.class, key)).isZero();
    }

    @Test
    void notFoundIsStoredAsADeterministicResult() {
        UUID a = api.createWallet(TestIds.unique("w"), 1_000);
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = body("from_account_id", a, "to_account_id", UUID.randomUUID(),
                "amount_minor", 10, "currency", "USD");

        ApiResponse first = api.post("/v1/transfers", request, key);
        ApiResponse second = api.post("/v1/transfers", request, key);
        assertThat(first.status()).isEqualTo(404);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void expiredKeysAreDeletedByCleanup(@Autowired IdempotencyService service) {
        String key = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO idempotency_keys (id, client_id, idempotency_key, endpoint, request_hash, status,
                    response_status, response_body, created_at, expires_at)
                VALUES (?, ?, ?, 'POST /v1/x', 'h', 'COMPLETED', 201, '{}', now() - interval '2 days',
                        now() - interval '1 day')
                """, UUID.randomUUID(), ADMIN_CLIENT, key);
        assertThat(service.deleteExpired()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?",
                Long.class, key)).isZero();
    }

    private UUID insertKey(String key, String hash, String lockedUntilSql) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO idempotency_keys (id, client_id, idempotency_key, endpoint, request_hash, status, "
                + "lock_owner, locked_until, created_at, expires_at) VALUES (?, ?, ?, 'POST /v1/payments', ?, "
                + "'IN_PROGRESS', ?, " + lockedUntilSql + ", now(), now() + interval '1 day')",
                id, ADMIN_CLIENT, key, hash, UUID.randomUUID());
        return id;
    }
}
