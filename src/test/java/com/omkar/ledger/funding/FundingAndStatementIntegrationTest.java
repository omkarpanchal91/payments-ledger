package com.omkar.ledger.funding;

import static com.omkar.ledger.support.LedgerApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.TestIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FundingAndStatementIntegrationTest extends IntegrationTestBase {

    @Test
    void fundingCreditsTheWalletAndDebitsSettlement() {
        long settlementBefore = api.balanceByCode(jdbc, "external_settlement:USD");
        UUID wallet = api.createWallet(TestIds.unique("w"), 0);

        ApiResponse r = api.fund(wallet, 2_500, "USD");
        assertThat(r.getText("object")).isEqualTo("funding");
        assertThat(r.getLong("amount_minor")).isEqualTo(2_500);

        assertThat(api.balance(wallet)).isEqualTo(2_500);
        assertThat(api.balanceByCode(jdbc, "external_settlement:USD")).isEqualTo(settlementBefore + 2_500);
    }

    @Test
    void statementIsNewestFirstAndCursorPaginated() {
        UUID wallet = api.createWallet(TestIds.unique("w"), 0);
        for (int i = 1; i <= 5; i++) {
            api.fund(wallet, i * 100L, "USD");
        }
        assertThat(api.balance(wallet)).isEqualTo(1_500);

        List<JsonNode> all = new ArrayList<>();
        ApiResponse page1 = api.get("/v1/accounts/" + wallet + "/postings?limit=2");
        assertThat(page1.status()).isEqualTo(200);
        assertThat(page1.getText("object")).isEqualTo("list");
        assertThat(page1.json().path("data")).hasSize(2);
        assertThat(page1.json().path("has_more").asBoolean()).isTrue();
        page1.json().path("data").forEach(all::add);

        String cursor = page1.json().path("data").get(1).path("id").asText();
        ApiResponse page2 = api.get("/v1/accounts/" + wallet + "/postings?limit=2&starting_after=" + cursor);
        assertThat(page2.json().path("data")).hasSize(2);
        assertThat(page2.json().path("has_more").asBoolean()).isTrue();
        page2.json().path("data").forEach(all::add);

        cursor = page2.json().path("data").get(1).path("id").asText();
        ApiResponse page3 = api.get("/v1/accounts/" + wallet + "/postings?limit=2&starting_after=" + cursor);
        assertThat(page3.json().path("data")).hasSize(1);
        assertThat(page3.json().path("has_more").asBoolean()).isFalse();
        page3.json().path("data").forEach(all::add);

        Set<String> ids = new HashSet<>();
        long total = 0;
        Instant previous = Instant.MAX;
        for (JsonNode posting : all) {
            ids.add(posting.path("id").asText());
            // A funding credits a LIABILITY wallet: stored as a negative (credit) amount.
            assertThat(posting.path("direction").asText()).isEqualTo("CREDIT");
            assertThat(posting.path("entry_type").asText()).isEqualTo("FUNDING");
            total += posting.path("amount_minor").asLong();
            Instant createdAt = Instant.parse(posting.path("created_at").asText());
            assertThat(createdAt).isBeforeOrEqualTo(previous);
            previous = createdAt;
        }
        assertThat(ids).hasSize(5);
        assertThat(total).isEqualTo(-1_500);
    }

    @Test
    void invalidCursorIs400() {
        UUID wallet = api.createWallet(TestIds.unique("w"), 100);
        ApiResponse r = api.get("/v1/accounts/" + wallet + "/postings?starting_after=" + UUID.randomUUID());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("invalid_cursor");
    }

    @Test
    void limitMustBeWithinRange() {
        UUID wallet = api.createWallet(TestIds.unique("w"), 0);
        assertThat(api.get("/v1/accounts/" + wallet + "/postings?limit=0").status()).isEqualTo(400);
        assertThat(api.get("/v1/accounts/" + wallet + "/postings?limit=101").status()).isEqualTo(400);
        assertThat(api.get("/v1/accounts/" + wallet + "/postings?limit=100").status()).isEqualTo(200);
    }

    @Test
    void statementOfUnknownAccountIs404() {
        assertThat(api.get("/v1/accounts/" + UUID.randomUUID() + "/postings").status()).isEqualTo(404);
    }

    @Test
    void onlyCreditNormalAccountsCanBeFunded() {
        UUID asset = api.createAccount(TestIds.unique("cash"), "ASSET", "USD", true).id();
        ApiResponse r = api.post("/v1/fundings", body("account_id", asset, "amount_minor", 100, "currency", "USD"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("invalid_funding_target");
    }

    @Test
    void currencyWithoutSettlementAccountIs422() {
        UUID jpy = api.createAccount(TestIds.unique("w_jpy"), "LIABILITY", "JPY", false).id();
        ApiResponse r = api.post("/v1/fundings", body("account_id", jpy, "amount_minor", 100, "currency", "JPY"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("unsupported_currency");
    }

    @Test
    void fundingInTheWrongCurrencyIs422() {
        UUID usdWallet = api.createWallet(TestIds.unique("w"), 0);
        ApiResponse r = api.post("/v1/fundings", body("account_id", usdWallet, "amount_minor", 100, "currency", "EUR"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("currency_mismatch");
    }

    @Test
    void fundingUnknownAccountIs404() {
        ApiResponse r = api.post("/v1/fundings", body("account_id", UUID.randomUUID(), "amount_minor", 100,
                "currency", "USD"));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.errorCode()).isEqualTo("account_not_found");
    }

    @Test
    void nonPositiveAmountIs400() {
        UUID wallet = api.createWallet(TestIds.unique("w"), 0);
        ApiResponse r = api.post("/v1/fundings", body("account_id", wallet, "amount_minor", 0, "currency", "USD"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("error").path("param").asText()).isEqualTo("amount_minor");
    }
}
