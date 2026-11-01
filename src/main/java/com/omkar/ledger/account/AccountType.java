package com.omkar.ledger.account;

/** The five classic account types and their normal balance side. */
public enum AccountType {
    ASSET(NormalSide.DEBIT),
    EXPENSE(NormalSide.DEBIT),
    LIABILITY(NormalSide.CREDIT),
    REVENUE(NormalSide.CREDIT),
    EQUITY(NormalSide.CREDIT);

    private final NormalSide normalSide;

    AccountType(NormalSide normalSide) {
        this.normalSide = normalSide;
    }

    public NormalSide normalSide() {
        return normalSide;
    }
}
