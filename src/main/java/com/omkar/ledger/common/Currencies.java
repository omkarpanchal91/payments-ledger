package com.omkar.ledger.common;

import java.util.Currency;
import java.util.regex.Pattern;

/** Validation for ISO 4217 currency codes coming from API requests. */
public final class Currencies {

    private static final Pattern FORMAT = Pattern.compile("^[A-Z]{3}$");

    private Currencies() {
    }

    public static String requireValid(String code, String param) {
        if (code == null || !FORMAT.matcher(code).matches()) {
            throw ApiException.badRequest("invalid_currency",
                    "Currency must be an upper-case ISO 4217 code such as USD.", param);
        }
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_currency", "Unknown currency: " + code + ".", param);
        }
        return code;
    }
}
