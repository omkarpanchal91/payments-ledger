package com.omkar.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omkar.ledger.common.ApiException;
import org.junit.jupiter.api.Test;

class FeeCalculatorTest {

    private final FeeCalculator calculator = new FeeCalculator(290, 30);

    @Test
    void appliesPercentagePlusFixedFee() {
        assertThat(calculator.feeFor(1_000, "USD")).isEqualTo(29 + 30);
        assertThat(calculator.feeFor(10_000, "USD")).isEqualTo(290 + 30);
        // 1234 * 2.9% = 35.786 -> 36
        assertThat(calculator.feeFor(1_234, "USD")).isEqualTo(36 + 30);
    }

    @Test
    void smallestPossiblePaymentIsOneUnitAboveTheFee() {
        // 32 * 2.9% = 0.928 -> 1, fee = 31 < 32
        assertThat(calculator.feeFor(32, "USD")).isEqualTo(31);
    }

    @Test
    void rejectsAmountsThatDoNotCoverTheFee() {
        assertThatThrownBy(() -> calculator.feeFor(1, "USD"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("amount_too_small");
        assertThatThrownBy(() -> calculator.feeFor(31, "USD")).isInstanceOf(ApiException.class);
    }

    @Test
    void handlesLargeAmounts() {
        long amount = 1_000_000_000_000_000L;
        assertThat(calculator.feeFor(amount, "USD")).isEqualTo(29_000_000_000_000_030L);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new FeeCalculator(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeeCalculator(10_001, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeeCalculator(100, -5)).isInstanceOf(IllegalArgumentException.class);
    }
}
