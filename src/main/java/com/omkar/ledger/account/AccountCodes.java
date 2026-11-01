package com.omkar.ledger.account;

/** Naming convention for well-known accounts. */
public final class AccountCodes {

    private AccountCodes() {
    }

    public static String customerFunds(String customerId) {
        return "customer_funds:" + customerId;
    }

    public static String merchantBalance(String merchantId) {
        return "merchant_balance:" + merchantId;
    }

    /** System account, one per currency, seeded by Flyway (V7). */
    public static String platformFees(String currency) {
        return "platform_fees:" + currency;
    }

    /** System account, one per currency, seeded by Flyway (V7). */
    public static String externalSettlement(String currency) {
        return "external_settlement:" + currency;
    }
}
