package com.omkar.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.omkar.ledger.account.AccountType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void debitAndCreditAreRawSigns() {
        assertThat(Leg.debit(id, 100).signedAmount(AccountType.LIABILITY)).isEqualTo(100);
        assertThat(Leg.credit(id, 100).signedAmount(AccountType.ASSET)).isEqualTo(-100);
    }

    @Test
    void increaseFollowsTheNormalSide() {
        assertThat(Leg.increase(id, 100).signedAmount(AccountType.ASSET)).isEqualTo(100);
        assertThat(Leg.increase(id, 100).signedAmount(AccountType.EXPENSE)).isEqualTo(100);
        assertThat(Leg.increase(id, 100).signedAmount(AccountType.LIABILITY)).isEqualTo(-100);
        assertThat(Leg.increase(id, 100).signedAmount(AccountType.REVENUE)).isEqualTo(-100);
        assertThat(Leg.increase(id, 100).signedAmount(AccountType.EQUITY)).isEqualTo(-100);
    }

    @Test
    void decreaseIsTheOppositeOfIncrease() {
        for (AccountType type : AccountType.values()) {
            assertThat(Leg.decrease(id, 42).signedAmount(type)).isEqualTo(-Leg.increase(id, 42).signedAmount(type));
        }
    }

    @Test
    void paymentLegsBalance() {
        // customer wallet -1000, merchant +941, platform fees +59 (all credit-normal)
        long sum = Leg.decrease(id, 1_000).signedAmount(AccountType.LIABILITY)
                + Leg.increase(id, 941).signedAmount(AccountType.LIABILITY)
                + Leg.increase(id, 59).signedAmount(AccountType.REVENUE);
        assertThat(sum).isZero();
    }

    @Test
    void fundingLegsBalance() {
        long sum = Leg.increase(id, 500).signedAmount(AccountType.ASSET)
                + Leg.increase(id, 500).signedAmount(AccountType.LIABILITY);
        assertThat(sum).isZero();
    }
}
