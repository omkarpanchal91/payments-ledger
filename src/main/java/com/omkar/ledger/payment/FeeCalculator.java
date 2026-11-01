package com.omkar.ledger.payment;

import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.Money;

/**
 * Platform fee: {@code round_half_up(amount * percentBps / 10_000) + fixedMinor}.
 * With the default 290 bps + 30: a 1000-cent ($10.00) payment pays 29 + 30 = 59 cents.
 */
public class FeeCalculator {

    private final int percentBps;
    private final long fixedMinor;

    public FeeCalculator(int percentBps, long fixedMinor) {
        if (percentBps < 0 || percentBps > 10_000 || fixedMinor < 0) {
            throw new IllegalArgumentException("Invalid fee configuration");
        }
        this.percentBps = percentBps;
        this.fixedMinor = fixedMinor;
    }

    public long feeFor(long amountMinor, String currency) {
        Money amount = Money.of(amountMinor, currency);
        Money fee = amount.percentageBps(percentBps).plus(Money.of(fixedMinor, currency));
        if (fee.amountMinor() >= amountMinor) {
            throw ApiException.unprocessable("amount_too_small",
                    "Amount must be larger than the fee (" + fee.amountMinor() + ").", "amount_minor");
        }
        return fee.amountMinor();
    }
}
