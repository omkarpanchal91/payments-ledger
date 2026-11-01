package com.omkar.ledger.outbox;

import java.util.Set;

public final class EventTypes {

    public static final String TRANSFER_COMPLETED = "transfer.completed";
    public static final String FUNDING_COMPLETED = "funding.completed";
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String REFUND_CREATED = "refund.created";

    /** Event types a merchant can subscribe a webhook endpoint to ("*" = all of them). */
    public static final Set<String> WEBHOOK_SUBSCRIBABLE = Set.of(PAYMENT_SUCCEEDED, REFUND_CREATED);
    public static final String WILDCARD = "*";

    private EventTypes() {
    }
}
