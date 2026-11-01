-- Transactional outbox: written in the same DB transaction as the money movement,
-- published to Kafka afterwards by OutboxRelay.
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,            -- also the event id consumers dedupe on
    aggregate_type TEXT        NOT NULL,
    aggregate_id   UUID        NOT NULL,        -- Kafka message key (per-aggregate ordering)
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INTEGER     NOT NULL DEFAULT 0,
    last_error     TEXT
);

-- Partial index: stays tiny because published rows drop out of it.
CREATE INDEX outbox_events_unpublished_idx ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX outbox_events_aggregate_idx ON outbox_events (aggregate_id);
