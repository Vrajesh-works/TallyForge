-- Outbound webhooks: merchant endpoints and one delivery row per (endpoint, event).
CREATE TABLE webhook_endpoints (
    id          UUID PRIMARY KEY,
    url         VARCHAR(500) NOT NULL,
    secret      VARCHAR(100) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE webhook_deliveries (
    id                UUID PRIMARY KEY,
    endpoint_id       UUID         NOT NULL REFERENCES webhook_endpoints (id),
    event_id          UUID         NOT NULL,
    event_type        VARCHAR(64)  NOT NULL,
    payload           JSONB        NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_status_code  INTEGER,
    last_error        VARCHAR(500),
    delivered_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (endpoint_id, event_id)
);

CREATE INDEX idx_webhook_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
