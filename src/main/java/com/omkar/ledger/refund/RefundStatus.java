package com.omkar.ledger.refund;

/**
 * Refunds are synchronous ledger movements, so a stored refund always succeeded. A refund that
 * fails validation (too large, insufficient merchant funds) is rejected and never stored; its
 * error response is kept by the idempotency layer.
 */
public enum RefundStatus {
    SUCCEEDED
}
