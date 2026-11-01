package com.omkar.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void addsAndSubtractsExactly() {
        assertThat(Money.of(10, "USD").plus(Money.of(20, "USD"))).isEqualTo(Money.of(30, "USD"));
        assertThat(Money.of(10, "USD").minus(Money.of(25, "USD"))).isEqualTo(Money.of(-15, "USD"));
        assertThat(Money.of(7, "USD").negate()).isEqualTo(Money.of(-7, "USD"));
    }

    @Test
    void rejectsMixedCurrencies() {
        assertThatThrownBy(() -> Money.of(1, "USD").plus(Money.of(1, "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void rejectsInvalidCurrencyCodes() {
        assertThatThrownBy(() -> Money.of(1, "usd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(1, "US")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowThrowsInsteadOfWrappingAround() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "USD").plus(Money.of(1, "USD")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, "USD").negate())
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void percentageRoundsHalfUp() {
        // 1000 * 2.9% = 29.0
        assertThat(Money.of(1_000, "USD").percentageBps(290).amountMinor()).isEqualTo(29);
        // 50 * 2.9% = 1.45 -> 1
        assertThat(Money.of(50, "USD").percentageBps(290).amountMinor()).isEqualTo(1);
        // 150 * 2.9% = 4.35 -> 4 ; 155 * 2.9% = 4.495 -> 4 ; 156 * 2.9% = 4.524 -> 5
        assertThat(Money.of(150, "USD").percentageBps(290).amountMinor()).isEqualTo(4);
        assertThat(Money.of(155, "USD").percentageBps(290).amountMinor()).isEqualTo(4);
        assertThat(Money.of(156, "USD").percentageBps(290).amountMinor()).isEqualTo(5);
        // exactly .5 rounds up: 50 * 1% = 0.5 -> 1
        assertThat(Money.of(50, "USD").percentageBps(100).amountMinor()).isEqualTo(1);
        // 1 minor unit
        assertThat(Money.of(1, "USD").percentageBps(290).amountMinor()).isZero();
    }

    @Test
    void percentageOfVeryLargeAmountsIsExact() {
        // Long.MAX_VALUE * 290 overflows a long, but BigDecimal keeps it exact:
        // 9223372036854775807 * 290 / 10000 = 267477789068788498.403 -> 267477789068788498
        long result = Money.of(Long.MAX_VALUE, "USD").percentageBps(290).amountMinor();
        assertThat(result).isEqualTo(267_477_789_068_788_498L);
    }

    @Test
    void signPredicates() {
        assertThat(Money.of(1, "USD").isPositive()).isTrue();
        assertThat(Money.of(-1, "USD").isNegative()).isTrue();
        assertThat(Money.of(0, "USD").isZero()).isTrue();
    }
}
