CREATE TABLE webhook_endpoints (
    id             UUID PRIMARY KEY,
    merchant_id    TEXT        NOT NULL,
    url            TEXT        NOT NULL,
    -- Base64 of 32 random bytes. Stored in plain text here; production would encrypt it (KMS).
    secret         TEXT        NOT NULL,
    enabled_events TEXT[]      NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'ENABLED' CHECK (status IN ('ENABLED', 'DISABLED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX webhook_endpoints_merchant_idx ON webhook_endpoints (merchant_id) WHERE status = 'ENABLED';

CREATE TABLE webhook_deliveries (
    id                 UUID PRIMARY KEY,
    event_id           UUID        NOT NULL REFERENCES outbox_events (id),
    endpoint_id        UUID        NOT NULL REFERENCES webhook_endpoints (id),
    status             TEXT        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'RETRYING', 'DEAD')),
    attempt_count      INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_response_code INTEGER,
    last_error         TEXT,
    dlq_published_at   TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A replayed Kafka message can never create a second delivery for the same endpoint.
    CONSTRAINT webhook_deliveries_event_endpoint_uq UNIQUE (event_id, endpoint_id)
);

CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at)
    WHERE status IN ('PENDING', 'RETRYING');
CREATE INDEX webhook_deliveries_dlq_idx ON webhook_deliveries (updated_at)
    WHERE status = 'DEAD' AND dlq_published_at IS NULL;
