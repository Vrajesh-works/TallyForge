-- Exactly-once responses for client retries. The key row is inserted in the
-- same transaction as the money movement and updated with the response, so a
-- committed key always has a response and a rolled-back attempt leaves no key.
CREATE TABLE idempotency_keys (
    scope            VARCHAR(64)  NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    response_status  INTEGER,
    response_body    JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX idx_idempotency_created ON idempotency_keys (created_at);
