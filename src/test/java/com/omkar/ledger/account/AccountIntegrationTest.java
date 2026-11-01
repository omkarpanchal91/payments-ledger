package com.omkar.ledger.account;

import static com.omkar.ledger.support.LedgerApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.TestIds;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountIntegrationTest extends IntegrationTestBase {

    @Test
    void createsAndFetchesAnAccount() {
        String code = TestIds.unique("customer_funds:c");
        ApiResponse created = api.createAccount(code, "LIABILITY", "USD", false);

        assertThat(created.getText("object")).isEqualTo("account");
        assertThat(created.getText("code")).isEqualTo(code);
        assertThat(created.getText("type")).isEqualTo("LIABILITY");
        assertThat(created.getText("normal_side")).isEqualTo("CREDIT");
        assertThat(created.getText("currency")).isEqualTo("USD");
        assertThat(created.getLong("balance_minor")).isZero();
        assertThat(created.getText("status")).isEqualTo("ACTIVE");
        assertThat(created.json().path("allow_negative").asBoolean()).isFalse();

        ApiResponse fetched = api.get("/v1/accounts/" + created.id());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.id()).isEqualTo(created.id());
        assertThat(fetched.getText("code")).isEqualTo(code);
    }

    @Test
    void enumValuesAreCaseInsensitive() {
        ApiResponse r = api.post("/v1/accounts", body("code", TestIds.unique("ops"), "type", "asset",
                "currency", "USD"));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.getText("type")).isEqualTo("ASSET");
        assertThat(r.getText("normal_side")).isEqualTo("DEBIT");
    }

    @Test
    void duplicateCodeIs422() {
        String code = TestIds.unique("dup");
        api.createAccount(code, "LIABILITY", "USD", false);
        ApiResponse r = api.post("/v1/accounts", body("code", code, "type", "LIABILITY", "currency", "USD"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.errorCode()).isEqualTo("account_code_taken");
        assertThat(r.json().path("error").path("param").asText()).isEqualTo("code");
    }

    @Test
    void invalidCurrencyIs400() {
        ApiResponse unknown = api.post("/v1/accounts", body("code", TestIds.unique("x"), "type", "LIABILITY",
                "currency", "ABC"));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.errorCode()).isEqualTo("invalid_currency");

        ApiResponse lowercase = api.post("/v1/accounts", body("code", TestIds.unique("x"), "type", "LIABILITY",
                "currency", "usd"));
        assertThat(lowercase.status()).isEqualTo(400);
        assertThat(lowercase.errorCode()).isEqualTo("invalid_currency");
    }

    @Test
    void invalidCodeIs400() {
        ApiResponse r = api.post("/v1/accounts", body("code", "has spaces!", "type", "LIABILITY", "currency", "USD"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("parameter_invalid");
        assertThat(r.json().path("error").path("param").asText()).isEqualTo("code");
    }

    @Test
    void unknownAccountIs404() {
        ApiResponse r = api.get("/v1/accounts/" + UUID.randomUUID());
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.errorCode()).isEqualTo("account_not_found");
    }

    @Test
    void malformedIdIs400() {
        ApiResponse r = api.get("/v1/accounts/not-a-uuid");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("parameter_invalid");
    }

    @Test
    void frozenAccountsCannotMoveMoney() {
        UUID from = api.createWallet(TestIds.unique("w"), 1_000);
        UUID to = api.createWallet(TestIds.unique("w"), 0);

        ApiResponse frozen = api.post("/v1/accounts/" + from + "/freeze", null);
        assertThat(frozen.status()).isEqualTo(200);
        assertThat(frozen.getText("status")).isEqualTo("FROZEN");

        ApiResponse blocked = api.transfer(from, to, 100);
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.errorCode()).isEqualTo("account_frozen");

        ApiResponse unfrozen = api.post("/v1/accounts/" + from + "/unfreeze", null);
        assertThat(unfrozen.status()).isEqualTo(200);
        assertThat(unfrozen.getText("status")).isEqualTo("ACTIVE");

        assertThat(api.transfer(from, to, 100).status()).isEqualTo(201);
        assertThat(api.balance(from)).isEqualTo(900);
        assertThat(api.balance(to)).isEqualTo(100);
    }

    @Test
    void freezeIncrementsTheOptimisticLockVersion() {
        UUID id = api.createWallet(TestIds.unique("w"), 0);
        Long before = jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Long.class, id);
        api.post("/v1/accounts/" + id + "/freeze", null);
        Long after = jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Long.class, id);
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void freezingUnknownAccountIs404() {
        ApiResponse r = api.post("/v1/accounts/" + UUID.randomUUID() + "/freeze", null);
        assertThat(r.status()).isEqualTo(404);
    }
}
