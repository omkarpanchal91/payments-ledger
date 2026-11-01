package com.omkar.ledger.refund;

import static com.omkar.ledger.support.LedgerApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.TestIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RefundIntegrationTest extends IntegrationTestBase {

    private record Setup(String customer, String merchant, UUID customerAccount, UUID merchantAccount,
                         UUID paymentId) {
    }

    /** Customer funded with 10,000; pays 1,000 (fee 59, merchant gets 941). */
    private Setup paidThousand() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        UUID customerAccount = api.createCustomer(customer, 10_000);
        UUID merchantAccount = api.createMerchant(merchant);
        ApiResponse payment = api.pay(customer, merchant, 1_000);
        assertThat(payment.status()).isEqualTo(201);
        return new Setup(customer, merchant, customerAccount, merchantAccount, payment.id());
    }

    private ApiResponse refund(UUID paymentId, Long amount) {
        Object request = amount == null ? null : body("amount_minor", amount);
        return api.post("/v1/payments/" + paymentId + "/refunds", request);
    }

    @Test
    void partialRefundsAddUpAndReturnTheFeeProportionally() {
        Setup s = paidThousand();
        long feesBefore = api.balanceByCode(jdbc, "platform_fees:USD");

        ApiResponse first = refund(s.paymentId(), 300L);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.getText("object")).isEqualTo("refund");
        assertThat(first.getLong("amount_minor")).isEqualTo(300);
        assertThat(first.getLong("fee_refund_minor")).isEqualTo(18);  // 59 * 300/1000 = 17.7 -> 18
        assertThat(api.balance(s.merchantAccount())).isEqualTo(941 - 282);
        assertThat(api.balance(s.customerAccount())).isEqualTo(9_300);

        ApiResponse second = refund(s.paymentId(), 700L);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.getLong("fee_refund_minor")).isEqualTo(41); // 59 - 18

        assertThat(api.balance(s.merchantAccount())).isZero();
        assertThat(api.balance(s.customerAccount())).isEqualTo(10_000);
        assertThat(api.balanceByCode(jdbc, "platform_fees:USD")).isEqualTo(feesBefore - 59);

        ApiResponse payment = api.get("/v1/payments/" + s.paymentId());
        assertThat(payment.getLong("refunded_minor")).isEqualTo(1_000);

        ApiResponse third = refund(s.paymentId(), 1L);
        assertThat(third.status()).isEqualTo(422);
        assertThat(third.errorCode()).isEqualTo("payment_fully_refunded");
    }

    @Test
    void omittedAmountRefundsEverythingLeft() {
        Setup s = paidThousand();
        assertThat(refund(s.paymentId(), 250L).status()).isEqualTo(201);

        ApiResponse rest = refund(s.paymentId(), null);
        assertThat(rest.status()).isEqualTo(201);
        assertThat(rest.getLong("amount_minor")).isEqualTo(750);
        assertThat(api.balance(s.customerAccount())).isEqualTo(10_000);
    }

    @Test
    void overRefundIs422() {
        Setup s = paidThousand();
        ApiResponse r = refund(s.paymentId(), 1_001L);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("refund_exceeds_payment");
        assertThat(r.json().path("error").path("param").asText()).isEqualTo("amount_minor");
    }

    @Test
    void failedPaymentsCannotBeRefunded() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        api.createCustomer(customer, 10);
        api.createMerchant(merchant);
        assertThat(api.pay(customer, merchant, 1_000).status()).isEqualTo(422);
        UUID failedId = jdbc.queryForObject("SELECT id FROM payments WHERE merchant_id = ?", UUID.class, merchant);

        ApiResponse r = refund(failedId, null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("payment_not_refundable");
    }

    @Test
    void unknownPaymentIs404() {
        ApiResponse r = refund(UUID.randomUUID(), 100L);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.errorCode()).isEqualTo("payment_not_found");
    }

    @Test
    void refundCanBeFetched() {
        Setup s = paidThousand();
        ApiResponse created = refund(s.paymentId(), 400L);

        ApiResponse fetched = api.get("/v1/refunds/" + created.id());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(created.body());
        assertThat(api.get("/v1/refunds/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void refundWritesAnEventKeyedByThePayment() {
        Setup s = paidThousand();
        refund(s.paymentId(), 100L);
        List<String> types = jdbc.queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at", String.class,
                s.paymentId());
        assertThat(types).containsExactly("payment.succeeded", "refund.created");
    }

    @Test
    void parallelRefundsNeverExceedThePayment() throws Exception {
        Setup s = paidThousand();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return refund(s.paymentId(), 300L);
            }));
        }
        start.countDown();
        Map<Integer, Integer> statuses = new TreeMap<>();
        for (Future<ApiResponse> f : futures) {
            ApiResponse r = f.get(1, TimeUnit.MINUTES);
            statuses.merge(r.status(), 1, Integer::sum);
            if (r.status() == 422) {
                assertThat(r.errorCode()).isEqualTo("refund_exceeds_payment");
            }
        }
        pool.shutdown();

        assertThat(statuses).containsEntry(201, 3).containsEntry(422, 7);
        assertThat(api.get("/v1/payments/" + s.paymentId()).getLong("refunded_minor")).isEqualTo(900);
        Long refunded = jdbc.queryForObject("SELECT SUM(amount_minor) FROM refunds WHERE payment_id = ?",
                Long.class, s.paymentId());
        assertThat(refunded).isEqualTo(900);
        // Fee shares: 18 (300), 17 (600 -> 35), 18 (900 -> 53) = 53; merchant returned 900 - 53 = 847
        assertThat(api.balance(s.merchantAccount())).isEqualTo(941 - 847);
        assertThat(api.balance(s.customerAccount())).isEqualTo(9_900);
    }
}
