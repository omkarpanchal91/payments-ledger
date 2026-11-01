package com.omkar.ledger.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount of money: a {@code long} count of minor units (cents, paise) plus an ISO 4217 code.
 *
 * <p>Never use double/float for money: 0.1 + 0.2 != 0.3 in binary floating point. All arithmetic
 * here is exact and overflow-checked ({@link Math#addExact} throws instead of silently wrapping
 * around, which in a ledger would be catastrophic).
 */
public record Money(long amountMinor, String currency) {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final BigDecimal BPS_DIVISOR = BigDecimal.valueOf(10_000);

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO 4217 code: " + currency);
        }
    }

    public static Money of(long amountMinor, String currency) {
        return new Money(amountMinor, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    /**
     * {@code round_half_up(amount * bps / 10_000)}. 1 bps = 0.01%, so 290 bps = 2.9%.
     * BigDecimal makes the intermediate product exact for any long amount.
     */
    public Money percentageBps(int bps) {
        if (bps < 0) {
            throw new IllegalArgumentException("bps must be >= 0");
        }
        long result = BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(bps))
                .divide(BPS_DIVISOR, 0, RoundingMode.HALF_UP)
                .longValueExact();
        return new Money(result, currency);
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
