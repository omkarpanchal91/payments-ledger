package com.omkar.ledger.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RefundFeeCalculatorTest {

    @Test
    void fullRefundReturnsTheWholeFee() {
        assertThat(RefundFeeCalculator.feeShare(1_000, 59, 0, 1_000)).isEqualTo(59);
    }

    @Test
    void partialRefundIsProportionalWithHalfUpRounding() {
        // 59 * 300 / 1000 = 17.7 -> 18
        assertThat(RefundFeeCalculator.feeShare(1_000, 59, 0, 300)).isEqualTo(18);
        // cumulative 59 * 1000/1000 = 59, minus 18 already returned
        assertThat(RefundFeeCalculator.feeShare(1_000, 59, 300, 700)).isEqualTo(41);
    }

    @Test
    void threeEqualPartsAddUpExactlyToTheFee() {
        long a = RefundFeeCalculator.feeShare(999, 10, 0, 333);
        long b = RefundFeeCalculator.feeShare(999, 10, 333, 333);
        long c = RefundFeeCalculator.feeShare(999, 10, 666, 333);
        assertThat(a + b + c).isEqualTo(10);
    }

    @Test
    void oneMinorUnitRefunds() {
        long total = 0;
        for (int refunded = 0; refunded < 100; refunded++) {
            long share = RefundFeeCalculator.feeShare(100, 33, refunded, 1);
            assertThat(share).isBetween(0L, 1L);
            total += share;
        }
        assertThat(total).isEqualTo(33);
    }

    @Test
    void randomSplitsAlwaysSumToFeeAndNeverExceedTheRefund() {
        Random random = new Random(42);
        for (int run = 0; run < 1_000; run++) {
            long amount = 32 + random.nextInt(1_000_000);
            long fee = random.nextLong(amount);
            long refunded = 0;
            long feeReturned = 0;
            while (refunded < amount) {
                long refund = 1 + random.nextLong(amount - refunded);
                long share = RefundFeeCalculator.feeShare(amount, fee, refunded, refund);
                assertThat(share).isBetween(0L, refund);
                feeReturned += share;
                refunded += refund;
            }
            assertThat(feeReturned).as("amount=%d fee=%d", amount, fee).isEqualTo(fee);
        }
    }

    @Test
    void largeAmountsDoNotOverflow() {
        long amount = Long.MAX_VALUE / 2;
        long fee = amount / 10;
        assertThat(RefundFeeCalculator.feeShare(amount, fee, 0, amount)).isEqualTo(fee);
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> RefundFeeCalculator.feeShare(1_000, 59, 900, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundFeeCalculator.feeShare(1_000, 59, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundFeeCalculator.feeShare(100, 100, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
