-- Accounts hold a cached balance (sum of all postings) for fast reads.
-- balance_minor uses the debit-positive convention: debit = +, credit = -.
CREATE TABLE accounts (
    id             UUID PRIMARY KEY,
    code           TEXT        NOT NULL UNIQUE,
    type           TEXT        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE', 'EQUITY')),
    currency       CHAR(3)     NOT NULL,
    balance_minor  BIGINT      NOT NULL DEFAULT 0,
    allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
    status         TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN')),
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
