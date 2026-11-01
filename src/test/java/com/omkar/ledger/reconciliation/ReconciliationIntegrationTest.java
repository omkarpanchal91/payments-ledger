package com.omkar.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.TestIds;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationIntegrationTest extends IntegrationTestBase {

    @Test
    void ledgerIsBalancedAfterNormalActivity() {
        String customer = TestIds.unique("c");
        String merchant = TestIds.unique("m");
        UUID customerAccount = api.createCustomer(customer, 5_000);
        api.createMerchant(merchant);
        UUID paymentId = api.pay(customer, merchant, 1_000).id();
        api.post("/v1/payments/" + paymentId + "/refunds", null);
        UUID other = api.createWallet(TestIds.unique("w"), 0);
        api.transfer(customerAccount, other, 123);

        ApiResponse report = api.get("/v1/admin/reconciliation");
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.json().path("balanced").asBoolean()).as(report.body()).isTrue();
        assertThat(report.json().path("account_mismatches")).isEmpty();
        assertThat(report.json().path("unbalanced_entries")).isEmpty();
        assertThat(report.json().path("currency_imbalances")).isEmpty();
        assertThat(report.getLong("accounts_checked")).isPositive();
    }

    @Test
    void detectsACachedBalanceThatDriftedFromThePostings() {
        UUID wallet = api.createWallet(TestIds.unique("w"), 1_000);
        jdbc.update("UPDATE accounts SET balance_minor = balance_minor + 7 WHERE id = ?", wallet);
        try {
            ApiResponse report = api.get("/v1/admin/reconciliation");
            assertThat(report.json().path("balanced").asBoolean()).isFalse();

            JsonNode mismatch = null;
            for (JsonNode m : report.json().path("account_mismatches")) {
                if (m.path("account_id").asText().equals(wallet.toString())) {
                    mismatch = m;
                }
            }
            assertThat(mismatch).as("mismatch for %s in %s", wallet, report.body()).isNotNull();
            assertThat(mismatch.path("cached_balance_minor").asLong() - mismatch.path("postings_sum_minor").asLong())
                    .isEqualTo(7);
        } finally {
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor - 7 WHERE id = ?", wallet);
        }
        assertThat(api.get("/v1/admin/reconciliation").json().path("balanced").asBoolean()).isTrue();
    }
}
