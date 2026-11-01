package com.omkar.ledger.account.dto;

import com.omkar.ledger.account.Account;
import com.omkar.ledger.account.AccountStatus;
import com.omkar.ledger.account.AccountType;
import com.omkar.ledger.account.NormalSide;
import java.time.Instant;
import java.util.UUID;

/**
 * @param balanceMinor       balance on the account's normal side (a funded customer wallet is positive)
 * @param signedBalanceMinor raw sum of postings, debit-positive (what the ledger stores)
 */
public record AccountResponse(
        UUID id,
        String object,
        String code,
        AccountType type,
        NormalSide normalSide,
        String currency,
        long balanceMinor,
        long signedBalanceMinor,
        boolean allowNegative,
        AccountStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), "account", a.getCode(), a.getType(), a.getType().normalSide(),
                a.getCurrency(), a.normalBalanceMinor(), a.getBalanceMinor(), a.isAllowNegative(), a.getStatus(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
