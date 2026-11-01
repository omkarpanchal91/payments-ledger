package com.omkar.ledger.refund;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much of the original fee goes back with a (partial) refund.
 *
 * <h2>Rounding rule</h2>
 * The fee is refunded proportionally, computed on the <em>cumulative</em> refunded amount:
 * <pre>
 *   feeRefundedAfter(r) = round_half_up(fee * r / amount)
 *   feeShare(refund)    = feeRefundedAfter(alreadyRefunded + refund) - feeRefundedAfter(alreadyRefunded)
 * </pre>
 * Rounding each partial refund independently would let rounding errors accumulate (three refunds
 * of 1/3 could return 1 cent more or less than the fee). The cumulative form telescopes: whatever
 * the split, once the payment is fully refunded exactly {@code fee} has been returned.
 * Because fee &lt; amount, a fee share never exceeds the refund itself, so the merchant's part
 * ({@code refund - feeShare}) is never negative.
 */
public final class RefundFeeCalculator {

    private RefundFeeCalculator() {
    }

    public static long feeShare(long paymentAmount, long fee, long alreadyRefunded, long refundAmount) {
        if (paymentAmount <= 0 || fee < 0 || fee >= paymentAmount || alreadyRefunded < 0 || refundAmount <= 0
                || Math.addExact(alreadyRefunded, refundAmount) > paymentAmount) {
            throw new IllegalArgumentException("Invalid refund fee inputs");
        }
        return feeRefundedAfter(paymentAmount, fee, alreadyRefunded + refundAmount)
                - feeRefundedAfter(paymentAmount, fee, alreadyRefunded);
    }

    private static long feeRefundedAfter(long paymentAmount, long fee, long refunded) {
        return BigDecimal.valueOf(fee)
                .multiply(BigDecimal.valueOf(refunded))
                .divide(BigDecimal.valueOf(paymentAmount), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
