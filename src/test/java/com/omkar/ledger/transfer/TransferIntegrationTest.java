package com.omkar.ledger.transfer;

import static com.omkar.ledger.support.LedgerApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.TestIds;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferIntegrationTest extends IntegrationTestBase {

    @Test
    void movesMoneyBetweenWallets() {
        UUID from = api.createWallet(TestIds.unique("w"), 10_000);
        UUID to = api.createWallet(TestIds.unique("w"), 0);

        ApiResponse r = api.post("/v1/transfers", body("from_account_id", from, "to_account_id", to,
                "amount_minor", 2_500, "currency", "USD", "description", "rent split"));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.getText("object")).isEqualTo("transfer");
        assertThat(r.getLong("amount_minor")).isEqualTo(2_500);
        assertThat(r.getText("description")).isEqualTo("rent split");
        assertThat(api.balance(from)).isEqualTo(7_500);
        assertThat(api.balance(to)).isEqualTo(2_500);

        // Wallets are liabilities: money leaving is a DEBIT (+), money arriving is a CREDIT (-).
        JsonNode fromLine = api.get("/v1/accounts/" + from + "/postings?limit=1").json().path("data").get(0);
        JsonNode toLine = api.get("/v1/accounts/" + to + "/postings?limit=1").json().path("data").get(0);
        assertThat(fromLine.path("amount_minor").asLong()).isEqualTo(2_500);
        assertThat(fromLine.path("direction").asText()).isEqualTo("DEBIT");
        assertThat(toLine.path("amount_minor").asLong()).isEqualTo(-2_500);
        assertThat(toLine.path("journal_entry_id").asText()).isEqualTo(r.id().toString());
    }

    @Test
    void writesATransferCompletedOutboxEvent() {
        UUID from = api.createWallet(TestIds.unique("w"), 1_000);
        UUID to = api.createWallet(TestIds.unique("w"), 0);
        UUID transferId = api.transfer(from, to, 100).id();

        String type = jdbc.queryForObject("SELECT event_type FROM outbox_events WHERE aggregate_id = ?",
                String.class, transferId);
        assertThat(type).isEqualTo("transfer.completed");
    }

    @Test
    void insufficientFundsIs422AndMovesNothing() {
        UUID from = api.createWallet(TestIds.unique("w"), 1_000);
        UUID to = api.createWallet(TestIds.unique("w"), 0);

        ApiResponse r = api.transfer(from, to, 1_001);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("insufficient_funds");
        assertThat(r.json().path("error").path("param").asText()).isEqualTo("amount_minor");
        assertThat(api.balance(from)).isEqualTo(1_000);
        assertThat(api.balance(to)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM postings WHERE account_id = ?", Long.class, to))
                .isZero();
    }

    @Test
    void exactBalanceCanBeTransferred() {
        UUID from = api.createWallet(TestIds.unique("w"), 1_000);
        UUID to = api.createWallet(TestIds.unique("w"), 0);
        assertThat(api.transfer(from, to, 1_000).status()).isEqualTo(201);
        assertThat(api.balance(from)).isZero();
    }

    @Test
    void allowNegativeAccountsMayGoBelowZero() {
        UUID credit = api.createAccount(TestIds.unique("credit_line"), "LIABILITY", "USD", true).id();
        UUID to = api.createWallet(TestIds.unique("w"), 0);
        assertThat(api.transfer(credit, to, 500).status()).isEqualTo(201);
        assertThat(api.balance(credit)).isEqualTo(-500);
    }

    @Test
    void sameAccountIs400() {
        UUID a = api.createWallet(TestIds.unique("w"), 1_000);
        ApiResponse r = api.transfer(a, a, 10);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("same_account");
    }

    @Test
    void unknownAccountIs404() {
        UUID a = api.createWallet(TestIds.unique("w"), 1_000);
        ApiResponse r = api.transfer(a, UUID.randomUUID(), 10);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.errorCode()).isEqualTo("account_not_found");
    }

    @Test
    void currencyMismatchIs422() {
        UUID usd = api.createWallet(TestIds.unique("w"), 1_000);
        UUID eur = api.createAccount(TestIds.unique("w_eur"), "LIABILITY", "EUR", false).id();
        ApiResponse r = api.transfer(usd, eur, 10);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("currency_mismatch");
    }

    @Test
    void accountsWithDifferentNormalSidesAreIncompatible() {
        UUID wallet = api.createWallet(TestIds.unique("w"), 1_000);
        UUID cash = api.createAccount(TestIds.unique("cash"), "ASSET", "USD", true).id();
        ApiResponse r = api.transfer(wallet, cash, 10);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("incompatible_accounts");
    }

    @Test
    void assetToAssetTransfersWork() {
        UUID bankA = api.createAccount(TestIds.unique("bank_a"), "ASSET", "USD", true).id();
        UUID bankB = api.createAccount(TestIds.unique("bank_b"), "ASSET", "USD", true).id();
        assertThat(api.transfer(bankA, bankB, 300).status()).isEqualTo(201);
        assertThat(api.balance(bankA)).isEqualTo(-300);
        assertThat(api.balance(bankB)).isEqualTo(300);
    }

    @Test
    void zeroOrNegativeAmountIs400() {
        UUID a = api.createWallet(TestIds.unique("w"), 1_000);
        UUID b = api.createWallet(TestIds.unique("w"), 0);
        assertThat(api.transfer(a, b, 0).status()).isEqualTo(400);
        assertThat(api.transfer(a, b, -5).status()).isEqualTo(400);
    }
}
