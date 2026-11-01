package com.omkar.ledger.account;

import com.omkar.ledger.account.dto.AccountResponse;
import com.omkar.ledger.account.dto.CreateAccountRequest;
import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.Currencies;
import com.omkar.ledger.common.PageResponse;
import com.omkar.ledger.common.Times;
import com.omkar.ledger.ledger.PostingQueryRepository;
import com.omkar.ledger.ledger.dto.PostingResponse;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    public static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accounts;
    private final PostingQueryRepository postings;
    private final Clock clock;

    public AccountService(AccountRepository accounts, PostingQueryRepository postings, Clock clock) {
        this.accounts = accounts;
        this.postings = postings;
        this.clock = clock;
    }

    /** {@code @Transactional} joins the transaction opened by the idempotency layer (like nested atomic()). */
    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        String currency = Currencies.requireValid(request.currency(), "currency");
        if (accounts.existsByCode(request.code())) {
            throw codeTaken(request.code());
        }
        var account = new Account(UUID.randomUUID(), request.code(), request.type(), currency,
                Boolean.TRUE.equals(request.allowNegative()), Times.now(clock));
        try {
            // Flush now so a concurrent insert of the same code fails here (unique constraint),
            // not later at commit with a less helpful error.
            accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw codeTaken(request.code());
        }
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {
        return AccountResponse.from(find(id));
    }

    /** Optimistic locking demo: no row lock, {@code @Version} detects concurrent writers. */
    @Transactional
    public AccountResponse freeze(UUID id) {
        Account account = find(id);
        account.freeze(Times.now(clock));
        accounts.flush();
        return AccountResponse.from(account);
    }

    @Transactional
    public AccountResponse unfreeze(UUID id) {
        Account account = find(id);
        account.unfreeze(Times.now(clock));
        accounts.flush();
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public PageResponse<PostingResponse> statement(UUID accountId, int limit, UUID startingAfter) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("parameter_invalid", "limit must be between 1 and " + MAX_PAGE_SIZE + ".",
                    "limit");
        }
        if (!accounts.existsById(accountId)) {
            throw accountNotFound(accountId);
        }
        List<PostingResponse> rows = postings.findPage(accountId, startingAfter, limit + 1);
        boolean hasMore = rows.size() > limit;
        return PageResponse.of(hasMore ? rows.subList(0, limit) : rows, hasMore);
    }

    private Account find(UUID id) {
        return accounts.findById(id).orElseThrow(() -> accountNotFound(id));
    }

    public static ApiException accountNotFound(UUID id) {
        return ApiException.notFound("account_not_found", "No account with id " + id + ".");
    }

    private static ApiException codeTaken(String code) {
        return ApiException.unprocessable("account_code_taken", "An account with code " + code + " already exists.",
                "code");
    }
}
