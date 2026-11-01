package com.omkar.ledger.transfer;

import com.omkar.ledger.account.AccountRepository;
import com.omkar.ledger.account.AccountService;
import com.omkar.ledger.account.AccountType;
import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.Currencies;
import com.omkar.ledger.ledger.JournalEntryType;
import com.omkar.ledger.ledger.LedgerEntryRequest;
import com.omkar.ledger.ledger.LedgerService;
import com.omkar.ledger.ledger.Leg;
import com.omkar.ledger.ledger.PostedEntry;
import com.omkar.ledger.outbox.EventTypes;
import com.omkar.ledger.outbox.OutboxWriter;
import com.omkar.ledger.transfer.dto.CreateTransferRequest;
import com.omkar.ledger.transfer.dto.TransferResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves value from one account to another.
 *
 * <p>"From" decreases and "to" increases, each on its own normal side. For two customer wallets
 * (LIABILITY, credit-normal) that is: debit the sender (+amount), credit the receiver (-amount).
 * Both accounts must have the same normal side, otherwise "decrease one, increase the other" would
 * not balance (moving value between an asset and a liability isn't a transfer, it's a funding or
 * payout). Locking, balance checks and deadlock avoidance live in {@link LedgerService}; retries on
 * deadlock/serialization failure live in {@code RetryingTransactionRunner}.
 */
@Service
public class TransferService {

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final OutboxWriter outbox;

    public TransferService(AccountRepository accounts, LedgerService ledger, OutboxWriter outbox) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    @Transactional
    public TransferResponse transfer(CreateTransferRequest request) {
        String currency = Currencies.requireValid(request.currency(), "currency");
        UUID from = request.fromAccountId();
        UUID to = request.toAccountId();
        if (from.equals(to)) {
            throw ApiException.badRequest("same_account", "from_account_id and to_account_id must differ.",
                    "to_account_id");
        }
        // Account type never changes, so reading it without a lock is safe.
        AccountType fromType = accounts.findTypeById(from).orElseThrow(() -> AccountService.accountNotFound(from));
        AccountType toType = accounts.findTypeById(to).orElseThrow(() -> AccountService.accountNotFound(to));
        if (fromType.normalSide() != toType.normalSide()) {
            throw ApiException.unprocessable("incompatible_accounts", "Cannot transfer between a " + fromType
                    + " and a " + toType + " account; both must have the same normal balance side.", null);
        }

        long amount = request.amountMinor();
        PostedEntry posted = ledger.post(new LedgerEntryRequest(
                JournalEntryType.TRANSFER, null, request.description(), currency,
                List.of(Leg.decrease(from, amount), Leg.increase(to, amount))));

        var response = new TransferResponse(posted.entry().getId(), "transfer", from, to, amount, currency,
                request.description(), posted.entry().getCreatedAt());
        outbox.write("transfer", posted.entry().getId(), EventTypes.TRANSFER_COMPLETED, response);
        return response;
    }
}
