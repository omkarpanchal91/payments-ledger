CREATE TABLE idempotency_keys (
    id              UUID PRIMARY KEY,
    client_id       TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    -- HTTP method + path, e.g. "POST /v1/payments". A key is scoped to one endpoint.
    endpoint        TEXT        NOT NULL,
    -- SHA-256 (hex) of the canonical JSON request body.
    request_hash    TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INTEGER,
    -- Stored as TEXT (not JSONB) on purpose: JSONB normalizes key order and whitespace,
    -- and a replay must return exactly the same bytes as the first response.
    response_body   TEXT,
    -- Random token of the request that currently owns the key. A request whose lock expired
    -- and was taken over can no longer complete the key (its UPDATE matches 0 rows).
    lock_owner      UUID,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_keys_scope_uq UNIQUE (client_id, idempotency_key, endpoint)
);

CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);
