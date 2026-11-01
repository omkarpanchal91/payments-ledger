package com.omkar.ledger.funding;

import com.omkar.ledger.account.AccountCodes;
import com.omkar.ledger.account.AccountRepository;
import com.omkar.ledger.account.AccountService;
import com.omkar.ledger.account.AccountType;
import com.omkar.ledger.account.NormalSide;
import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.Currencies;
import com.omkar.ledger.funding.dto.CreateFundingRequest;
import com.omkar.ledger.funding.dto.FundingResponse;
import com.omkar.ledger.ledger.JournalEntryType;
import com.omkar.ledger.ledger.LedgerEntryRequest;
import com.omkar.ledger.ledger.LedgerService;
import com.omkar.ledger.ledger.Leg;
import com.omkar.ledger.ledger.PostedEntry;
import com.omkar.ledger.outbox.EventTypes;
import com.omkar.ledger.outbox.OutboxWriter;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money entering the system, e.g. a customer tops up their wallet by card or bank transfer.
 *
 * <pre>
 *   external_settlement:USD (ASSET)       debit  +amount   (we now hold the cash)
 *   customer_funds:c_123   (LIABILITY)    credit -amount   (we now owe it to the customer)
 * </pre>
 * Both accounts increase; the entry balances because one is debit-normal and one credit-normal.
 */
@Service
public class FundingService {

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final OutboxWriter outbox;

    public FundingService(AccountRepository accounts, LedgerService ledger, OutboxWriter outbox) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    @Transactional
    public FundingResponse fund(CreateFundingRequest request) {
        String currency = Currencies.requireValid(request.currency(), "currency");
        UUID target = request.accountId();
        AccountType type = accounts.findTypeById(target).orElseThrow(() -> AccountService.accountNotFound(target));
        if (type.normalSide() != NormalSide.CREDIT) {
            throw ApiException.unprocessable("invalid_funding_target",
                    "Only credit-normal accounts (e.g. LIABILITY wallets) can be funded; this one is " + type + ".",
                    "account_id");
        }
        UUID settlement = accounts.findIdByCode(AccountCodes.externalSettlement(currency))
                .orElseThrow(() -> ApiException.unprocessable("unsupported_currency",
                        "Fundings in " + currency + " are not supported (no settlement account).", "currency"));

        long amount = request.amountMinor();
        PostedEntry posted = ledger.post(new LedgerEntryRequest(
                JournalEntryType.FUNDING, null, request.description(), currency,
                List.of(Leg.increase(settlement, amount), Leg.increase(target, amount))));

        var response = new FundingResponse(posted.entry().getId(), "funding", target, settlement, amount, currency,
                request.description(), posted.entry().getCreatedAt());
        outbox.write("funding", posted.entry().getId(), EventTypes.FUNDING_COMPLETED, response);
        return response;
    }
}
