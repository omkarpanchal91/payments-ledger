package com.omkar.ledger.payment;

import com.omkar.ledger.account.AccountCodes;
import com.omkar.ledger.account.AccountRepository;
import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.Currencies;
import com.omkar.ledger.common.LedgerMetrics;
import com.omkar.ledger.common.Times;
import com.omkar.ledger.idempotency.Outcome;
import com.omkar.ledger.ledger.InsufficientFundsException;
import com.omkar.ledger.ledger.JournalEntryType;
import com.omkar.ledger.ledger.LedgerEntryRequest;
import com.omkar.ledger.ledger.LedgerService;
import com.omkar.ledger.ledger.Leg;
import com.omkar.ledger.ledger.PostedEntry;
import com.omkar.ledger.outbox.EventTypes;
import com.omkar.ledger.outbox.OutboxWriter;
import com.omkar.ledger.payment.dto.CreatePaymentRequest;
import com.omkar.ledger.payment.dto.PaymentResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer pays merchant; the platform keeps a fee. One journal entry, three postings:
 *
 * <pre>
 *   customer_funds:&lt;customer&gt;    decrease  amount          (debit,  +amount)
 *   merchant_balance:&lt;merchant&gt;  increase  amount - fee    (credit, -(amount - fee))
 *   platform_fees:&lt;CUR&gt;          increase  fee             (credit, -fee)
 *                                                    sum = 0
 * </pre>
 */
@Service
public class PaymentService {

    private final AccountRepository accounts;
    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final OutboxWriter outbox;
    private final FeeCalculator feeCalculator;
    private final LedgerMetrics metrics;
    private final Clock clock;

    public PaymentService(AccountRepository accounts, PaymentRepository payments, LedgerService ledger,
                          OutboxWriter outbox, FeeCalculator feeCalculator, LedgerMetrics metrics, Clock clock) {
        this.accounts = accounts;
        this.payments = payments;
        this.ledger = ledger;
        this.outbox = outbox;
        this.feeCalculator = feeCalculator;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Returns an {@link Outcome} instead of throwing on insufficient funds: the FAILED payment row and
     * the stored idempotent 422 response must be committed, and throwing would roll them back.
     */
    @Transactional
    public Outcome<PaymentResponse> create(CreatePaymentRequest request) {
        String currency = Currencies.requireValid(request.currency(), "currency");
        long amount = request.amountMinor();
        long fee = feeCalculator.feeFor(amount, currency);

        UUID customerAccount = accounts.findIdByCode(AccountCodes.customerFunds(request.customerId()))
                .orElseThrow(() -> ApiException.notFound("customer_account_not_found",
                        "No account " + AccountCodes.customerFunds(request.customerId()) + "."));
        UUID merchantAccount = accounts.findIdByCode(AccountCodes.merchantBalance(request.merchantId()))
                .orElseThrow(() -> ApiException.notFound("merchant_account_not_found",
                        "No account " + AccountCodes.merchantBalance(request.merchantId()) + "."));
        UUID feesAccount = accounts.findIdByCode(AccountCodes.platformFees(currency))
                .orElseThrow(() -> ApiException.unprocessable("unsupported_currency",
                        "Payments in " + currency + " are not supported.", "currency"));

        UUID paymentId = UUID.randomUUID();
        Instant now = Times.now(clock);
        try {
            PostedEntry posted = ledger.post(new LedgerEntryRequest(
                    JournalEntryType.PAYMENT, paymentId, "Payment " + paymentId, currency,
                    List.of(Leg.decrease(customerAccount, amount),
                            Leg.increase(merchantAccount, amount - fee),
                            Leg.increase(feesAccount, fee))));

            Payment payment = payments.save(Payment.succeeded(paymentId, request.merchantId(), request.customerId(),
                    amount, fee, currency, posted.entry().getId(), now));
            var response = PaymentResponse.from(payment);
            outbox.write("payment", paymentId, EventTypes.PAYMENT_SUCCEEDED, response);
            metrics.paymentCreated("succeeded");
            return Outcome.success(response);
        } catch (InsufficientFundsException e) {
            // LedgerService validated before mutating anything, so committing here is safe.
            payments.save(Payment.failed(paymentId, request.merchantId(), request.customerId(), amount, currency,
                    e.getCode(), now));
            metrics.paymentCreated("failed");
            return Outcome.failure(e);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID id) {
        return payments.findById(id).map(PaymentResponse::from)
                .orElseThrow(() -> ApiException.notFound("payment_not_found", "No payment with id " + id + "."));
    }
}
