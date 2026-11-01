package com.omkar.ledger.account;

/**
 * Which side "increases" an account. Postings are stored debit-positive (debit = +, credit = -).
 * A DEBIT-normal account (asset/expense) grows with debits; a CREDIT-normal account
 * (liability/revenue/equity) grows with credits, so its human-readable balance is the negated sum.
 */
public enum NormalSide {
    DEBIT(1),
    CREDIT(-1);

    private final int sign;

    NormalSide(int sign) {
        this.sign = sign;
    }

    /** Converts a debit-positive signed amount into this side's natural (normal) amount, and back. */
    public long toNormal(long signedAmount) {
        return Math.multiplyExact(signedAmount, (long) sign);
    }

    public long fromNormal(long normalAmount) {
        return Math.multiplyExact(normalAmount, (long) sign);
    }
}
