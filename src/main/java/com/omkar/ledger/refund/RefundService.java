package com.omkar.ledger.refund;

import com.omkar.ledger.account.AccountCodes;
import com.omkar.ledger.account.AccountRepository;
import com.omkar.ledger.common.ApiException;
import com.omkar.ledger.common.Times;
import com.omkar.ledger.ledger.JournalEntryType;
import com.omkar.ledger.ledger.LedgerEntryRequest;
import com.omkar.ledger.ledger.LedgerService;
import com.omkar.ledger.ledger.Leg;
import com.omkar.ledger.ledger.PostedEntry;
import com.omkar.ledger.outbox.EventTypes;
import com.omkar.ledger.outbox.OutboxWriter;
import com.omkar.ledger.payment.Payment;
import com.omkar.ledger.payment.PaymentRepository;
import com.omkar.ledger.payment.PaymentStatus;
import com.omkar.ledger.refund.dto.CreateRefundRequest;
import com.omkar.ledger.refund.dto.RefundResponse;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full or partial refund of a payment. Postings (the reverse of the payment, fee refunded pro rata):
 *
 * <pre>
 *   merchant_balance:&lt;merchant&gt;  decrease  refund - feeShare
 *   platform_fees:&lt;CUR&gt;          decrease  feeShare
 *   customer_funds:&lt;customer&gt;    increase  refund
 * </pre>
 *
 * <p><b>Concurrency.</b> Two parallel refunds of 600 on a 1000 payment must not both pass the
 * "refund &lt;= remaining" check. We lock the payment row first (SELECT ... FOR UPDATE); the second
 * refund waits, then reads refunded_minor = 600 and is rejected. Lock order is always payment row,
 * then accounts (in id order), so refunds can't deadlock with each other or with transfers.
 */
@Service
public class RefundService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final OutboxWriter outbox;
    private final EntityManager entityManager;
    private final Clock clock;

    public RefundService(PaymentRepository payments, RefundRepository refunds, AccountRepository accounts,
                         LedgerService ledger, OutboxWriter outbox, EntityManager entityManager, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.accounts = accounts;
        this.ledger = ledger;
        this.outbox = outbox;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public RefundResponse refund(UUID paymentId, CreateRefundRequest request) {
        Payment payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> ApiException.notFound("payment_not_found", "No payment with id " + paymentId + "."));
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw ApiException.unprocessable("payment_not_refundable", "Only succeeded payments can be refunded.", null);
        }
        long remaining = payment.refundableMinor();
        if (remaining == 0) {
            throw ApiException.unprocessable("payment_fully_refunded", "This payment is already fully refunded.", null);
        }
        long amount = request.amountMinor() == null ? remaining : request.amountMinor();
        if (amount > remaining) {
            throw ApiException.unprocessable("refund_exceeds_payment",
                    "Refund of " + amount + " exceeds the refundable amount of " + remaining + ".", "amount_minor");
        }

        long feeShare = RefundFeeCalculator.feeShare(payment.getAmountMinor(), payment.getFeeMinor(),
                payment.getRefundedMinor(), amount);
        long merchantShare = amount - feeShare;
        String currency = payment.getCurrency();

        UUID customerAccount = requireAccount(AccountCodes.customerFunds(payment.getCustomerId()));
        UUID merchantAccount = requireAccount(AccountCodes.merchantBalance(payment.getMerchantId()));
        UUID feesAccount = requireAccount(AccountCodes.platformFees(currency));

        List<Leg> legs = new ArrayList<>(3);
        if (merchantShare > 0) {
            legs.add(Leg.decrease(merchantAccount, merchantShare));
        }
        if (feeShare > 0) {
            legs.add(Leg.decrease(feesAccount, feeShare));
        }
        legs.add(Leg.increase(customerAccount, amount));

        UUID refundId = UUID.randomUUID();
        Instant now = Times.now(clock);
        PostedEntry posted = ledger.post(new LedgerEntryRequest(
                JournalEntryType.REFUND, refundId, "Refund " + refundId + " of payment " + paymentId, currency, legs));

        payment.recordRefund(amount, now);
        var refund = new Refund(refundId, paymentId, amount, feeShare, currency, posted.entry().getId(), now);
        entityManager.persist(refund);

        var response = RefundResponse.from(refund, payment.getMerchantId());
        // Aggregate = the payment, so payment.succeeded and refund.created share a Kafka key (and partition)
        // and are consumed in order.
        outbox.write("payment", paymentId, EventTypes.REFUND_CREATED, response);
        return response;
    }

    @Transactional(readOnly = true)
    public RefundResponse get(UUID id) {
        Refund refund = refunds.findById(id)
                .orElseThrow(() -> ApiException.notFound("refund_not_found", "No refund with id " + id + "."));
        String merchantId = payments.findById(refund.getPaymentId()).map(Payment::getMerchantId).orElse(null);
        return RefundResponse.from(refund, merchantId);
    }

    private UUID requireAccount(String code) {
        return accounts.findIdByCode(code)
                .orElseThrow(() -> new IllegalStateException("Account " + code + " disappeared"));
    }
}
