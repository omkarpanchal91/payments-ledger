package com.omkar.ledger.ledger;

import com.omkar.ledger.account.Account;
import com.omkar.ledger.account.AccountService;
import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.Times;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only component that writes postings and changes balances. Every money movement (funding,
 * transfer, payment, refund) goes through {@link #post}, so the double-entry rules live in one place.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Validate shape: at least two legs, positive amounts, distinct accounts.</li>
 *   <li>Lock all involved account rows with {@code SELECT ... FOR UPDATE} <b>in ascending id
 *   order</b>. A deadlock needs a cycle: T1 holds A and waits for B while T2 holds B and waits for A.
 *   If every transaction acquires locks in the same global order, T2 must also take A before B,
 *   so the cycle can't form. (Transfers A->B and B->A both lock min(A,B) first.)</li>
 *   <li>Convert legs to signed postings (debit +, credit -) and check they sum to exactly zero.</li>
 *   <li>Check every account is ACTIVE, in the entry's currency, and won't go negative.</li>
 *   <li>Only then mutate: update cached balances and insert the entry + postings.</li>
 * </ol>
 * All checks happen before any mutation, which lets PaymentService catch
 * {@link InsufficientFundsException} and still commit a FAILED payment without half-applied balances.
 *
 * <p>{@code Propagation.MANDATORY}: must be called inside an existing transaction (the caller's),
 * because the postings must commit atomically with the payment row, outbox event and idempotency
 * record. {@code noRollbackFor} stops Spring from marking that outer transaction rollback-only when
 * insufficient funds is thrown, so the caller decides what to do.
 */
@Service
public class LedgerService {

    private final EntityManager entityManager;
    private final Clock clock;

    public LedgerService(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = InsufficientFundsException.class)
    public PostedEntry post(LedgerEntryRequest request) {
        validateShape(request);

        List<Leg> legs = request.legs();
        Map<UUID, Account> locked = lockInIdOrder(legs);

        long[] signedAmounts = new long[legs.size()];
        long sum = 0;
        for (int i = 0; i < legs.size(); i++) {
            Account account = locked.get(legs.get(i).accountId());
            requireUsable(account, request.currency());
            signedAmounts[i] = legs.get(i).signedAmount(account.getType());
            sum = Math.addExact(sum, signedAmounts[i]);
        }
        if (sum != 0) {
            throw new UnbalancedEntryException(sum);
        }

        for (int i = 0; i < legs.size(); i++) {
            Account account = locked.get(legs.get(i).accountId());
            long newBalance = Math.addExact(account.getBalanceMinor(), signedAmounts[i]);
            if (!account.isAllowNegative() && account.getType().normalSide().toNormal(newBalance) < 0) {
                throw new InsufficientFundsException(account.getCode());
            }
        }

        Instant now = Times.now(clock);
        var entry = new JournalEntry(UUID.randomUUID(), request.type(), request.referenceId(),
                request.description(), request.currency(), now);
        entityManager.persist(entry);

        List<Posting> postings = new ArrayList<>(legs.size());
        for (int i = 0; i < legs.size(); i++) {
            Account account = locked.get(legs.get(i).accountId());
            long signed = signedAmounts[i];
            account.applyPosting(signed, now);
            var posting = new Posting(UUID.randomUUID(), entry.getId(), account.getId(), signed, now);
            entityManager.persist(posting);
            postings.add(posting);
        }
        return new PostedEntry(entry, List.copyOf(postings));
    }

    private static void validateShape(LedgerEntryRequest request) {
        if (request.legs().size() < 2) {
            throw new IllegalArgumentException("A journal entry needs at least two legs");
        }
        Set<UUID> seen = new HashSet<>();
        for (Leg leg : request.legs()) {
            if (leg.amountMinor() <= 0) {
                throw ApiException.badRequest("amount_invalid", "Amounts must be positive.", "amount_minor");
            }
            if (!seen.add(leg.accountId())) {
                throw new IllegalArgumentException("Each account may appear only once per journal entry");
            }
        }
    }

    private Map<UUID, Account> lockInIdOrder(List<Leg> legs) {
        List<UUID> ids = legs.stream().map(Leg::accountId).sorted().toList();
        Map<UUID, Account> locked = new HashMap<>();
        for (UUID id : ids) {
            // SELECT ... FROM accounts WHERE id = ? FOR UPDATE (blocks while another tx holds the row)
            Account account = entityManager.find(Account.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (account == null) {
                throw AccountService.accountNotFound(id);
            }
            locked.put(id, account);
        }
        return locked;
    }

    private static void requireUsable(Account account, String currency) {
        if (!account.isActive()) {
            throw ApiException.unprocessable("account_frozen", "Account " + account.getCode() + " is frozen.", null);
        }
        if (!account.getCurrency().equals(currency)) {
            throw ApiException.unprocessable("currency_mismatch", "Account " + account.getCode() + " holds "
                    + account.getCurrency() + " but the request is in " + currency + ".", "currency");
        }
    }
}
