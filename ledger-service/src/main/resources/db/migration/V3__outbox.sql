-- Transactional outbox: events are written in the same transaction as the
-- ledger rows they describe, then relayed to Kafka by a poller.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    seq             BIGSERIAL    NOT NULL UNIQUE,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;
