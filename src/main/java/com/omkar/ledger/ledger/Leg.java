package com.omkar.ledger.ledger;

import com.omkar.ledger.account.AccountType;
import java.util.UUID;

/**
 * One side of a money movement as requested by a business service, before it becomes a signed
 * posting. {@code amountMinor} is always positive; the variant says which way it goes.
 *
 * <ul>
 *   <li>{@link Debit}/{@link Credit}: raw accounting direction (+ / -).</li>
 *   <li>{@link Increase}/{@link Decrease}: business direction relative to the account's normal
 *   side, e.g. "the customer wallet goes down by 10". LedgerService converts these to debit/credit
 *   after it has locked the account and knows its type.</li>
 * </ul>
 *
 * <p>A sealed interface lists all its implementations, so a {@code switch} over it is checked for
 * exhaustiveness by the compiler (similar to a tagged union / typing.Literal-discriminated union).
 */
public sealed interface Leg {

    UUID accountId();

    long amountMinor();

    /** The debit-positive amount for an account of the given type. */
    long signedAmount(AccountType accountType);

    record Debit(UUID accountId, long amountMinor) implements Leg {
        @Override
        public long signedAmount(AccountType accountType) {
            return amountMinor;
        }
    }

    record Credit(UUID accountId, long amountMinor) implements Leg {
        @Override
        public long signedAmount(AccountType accountType) {
            return Math.negateExact(amountMinor);
        }
    }

    record Increase(UUID accountId, long amountMinor) implements Leg {
        @Override
        public long signedAmount(AccountType accountType) {
            return accountType.normalSide().fromNormal(amountMinor);
        }
    }

    record Decrease(UUID accountId, long amountMinor) implements Leg {
        @Override
        public long signedAmount(AccountType accountType) {
            return accountType.normalSide().fromNormal(Math.negateExact(amountMinor));
        }
    }

    static Leg debit(UUID accountId, long amountMinor) {
        return new Debit(accountId, amountMinor);
    }

    static Leg credit(UUID accountId, long amountMinor) {
        return new Credit(accountId, amountMinor);
    }

    static Leg increase(UUID accountId, long amountMinor) {
        return new Increase(accountId, amountMinor);
    }

    static Leg decrease(UUID accountId, long amountMinor) {
        return new Decrease(accountId, amountMinor);
    }
}
