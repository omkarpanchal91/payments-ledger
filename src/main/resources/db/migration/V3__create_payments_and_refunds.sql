CREATE TABLE payments (
    id               UUID PRIMARY KEY,
    merchant_id      TEXT        NOT NULL,
    customer_id      TEXT        NOT NULL,
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    fee_minor        BIGINT      NOT NULL CHECK (fee_minor >= 0),
    currency         CHAR(3)     NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    failure_code     TEXT,
    -- Defense in depth: even if application logic were wrong, the DB refuses over-refunds.
    refunded_minor   BIGINT      NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0 AND refunded_minor <= amount_minor),
    journal_entry_id UUID REFERENCES journal_entries (id),
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX payments_merchant_idx ON payments (merchant_id, created_at);

CREATE TABLE refunds (
    id               UUID PRIMARY KEY,
    payment_id       UUID        NOT NULL REFERENCES payments (id),
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    -- Part of the refund taken back from platform_fees (proportional fee refund).
    fee_refund_minor BIGINT      NOT NULL CHECK (fee_refund_minor >= 0),
    currency         CHAR(3)     NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('SUCCEEDED')),
    journal_entry_id UUID        NOT NULL REFERENCES journal_entries (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX refunds_payment_idx ON refunds (payment_id);
