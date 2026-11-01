package com.omkar.ledger.payment;

import static com.omkar.ledger.support.LedgerApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.TestIds;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentIntegrationTest extends IntegrationTestBase {

    @Test
    void paymentMovesMoneyAndChargesTheFee() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        UUID customerAccount = api.createCustomer(customer, 10_000);
        UUID merchantAccount = api.createMerchant(merchant);
        long feesBefore = api.balanceByCode(jdbc, "platform_fees:USD");

        ApiResponse r = api.pay(customer, merchant, 1_000);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.getText("object")).isEqualTo("payment");
        assertThat(r.getText("status")).isEqualTo("SUCCEEDED");
        assertThat(r.getLong("amount_minor")).isEqualTo(1_000);
        assertThat(r.getLong("fee_minor")).isEqualTo(59);   // 2.9% of 1000 = 29, + 30
        assertThat(r.getLong("net_minor")).isEqualTo(941);
        assertThat(r.getLong("refunded_minor")).isZero();
        assertThat(r.getText("journal_entry_id")).isNotNull();

        assertThat(api.balance(customerAccount)).isEqualTo(9_000);
        assertThat(api.balance(merchantAccount)).isEqualTo(941);
        assertThat(api.balanceByCode(jdbc, "platform_fees:USD")).isEqualTo(feesBefore + 59);

        List<Map<String, Object>> postings = jdbc.queryForList(
                "SELECT amount_minor FROM postings WHERE journal_entry_id = ?",
                UUID.fromString(r.getText("journal_entry_id")));
        assertThat(postings).hasSize(3);
        assertThat(postings.stream().mapToLong(p -> ((Number) p.get("amount_minor")).longValue()).sum()).isZero();

        ApiResponse fetched = api.get("/v1/payments/" + r.id());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.getLong("fee_minor")).isEqualTo(59);
        assertThat(fetched.getText("merchant_id")).isEqualTo(merchant);
    }

    @Test
    void writesAPaymentSucceededOutboxEvent() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        api.createCustomer(customer, 10_000);
        api.createMerchant(merchant);
        UUID paymentId = api.pay(customer, merchant, 1_000).id();

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT event_type, payload->>'merchant_id' AS merchant FROM outbox_events WHERE aggregate_id = ?",
                paymentId);
        assertThat(event.get("event_type")).isEqualTo("payment.succeeded");
        assertThat(event.get("merchant")).isEqualTo(merchant);
    }

    @Test
    void insufficientFundsStoresAFailedPaymentWithoutPostings() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        UUID customerAccount = api.createCustomer(customer, 500);
        UUID merchantAccount = api.createMerchant(merchant);

        ApiResponse r = api.pay(customer, merchant, 1_000);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("insufficient_funds");
        assertThat(api.balance(customerAccount)).isEqualTo(500);
        assertThat(api.balance(merchantAccount)).isZero();

        Map<String, Object> payment = jdbc.queryForMap(
                "SELECT status, fee_minor, failure_code, journal_entry_id FROM payments WHERE merchant_id = ?", merchant);
        assertThat(payment.get("status")).isEqualTo("FAILED");
        assertThat(((Number) payment.get("fee_minor")).longValue()).isZero();
        assertThat(payment.get("failure_code")).isEqualTo("insufficient_funds");
        assertThat(payment.get("journal_entry_id")).isNull();
    }

    @Test
    void unknownCustomerOrMerchantIs404() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        api.createCustomer(customer, 1_000);

        ApiResponse noCustomer = api.pay(TestIds.unique("ghost"), merchant, 100);
        assertThat(noCustomer.status()).isEqualTo(404);
        assertThat(noCustomer.errorCode()).isEqualTo("customer_account_not_found");

        ApiResponse noMerchant = api.pay(customer, merchant, 100);
        assertThat(noMerchant.status()).isEqualTo(404);
        assertThat(noMerchant.errorCode()).isEqualTo("merchant_account_not_found");
    }

    @Test
    void amountMustExceedTheFee() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        api.createCustomer(customer, 1_000);
        api.createMerchant(merchant);
        ApiResponse r = api.pay(customer, merchant, 30);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("amount_too_small");
    }

    @Test
    void unsupportedCurrencyIs422() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        api.createCustomer(customer, 1_000);
        api.createMerchant(merchant);
        ApiResponse r = api.post("/v1/payments", body("customer_id", customer, "merchant_id", merchant,
                "amount_minor", 500, "currency", "JPY"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("unsupported_currency");
    }

    @Test
    void invalidRequestsAre400() {
        assertThat(api.post("/v1/payments", body("customer_id", "c", "merchant_id", "m", "amount_minor", -1,
                "currency", "USD")).status()).isEqualTo(400);
        assertThat(api.post("/v1/payments", body("customer_id", "bad id!", "merchant_id", "m", "amount_minor", 100,
                "currency", "USD")).status()).isEqualTo(400);
    }

    @Test
    void unknownPaymentIs404() {
        ApiResponse r = api.get("/v1/payments/" + UUID.randomUUID());
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.errorCode()).isEqualTo("payment_not_found");
    }
}
