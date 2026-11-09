-- Card payments: a state machine on top of the ledger's two-phase transfers.
CREATE TABLE payments (
    id                   UUID PRIMARY KEY,
    merchant_account_id  UUID         NOT NULL REFERENCES accounts (id),
    amount               BIGINT       NOT NULL CHECK (amount > 0),
    captured_amount      BIGINT       NOT NULL DEFAULT 0 CHECK (captured_amount >= 0),
    refunded_amount      BIGINT       NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    currency             VARCHAR(3)   NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status               VARCHAR(24)  NOT NULL,
    card_last4           VARCHAR(4),
    gateway              VARCHAR(32)  NOT NULL,
    gateway_reference    VARCHAR(100),
    hold_transfer_id     UUID REFERENCES transfers (id),
    failure_reason       VARCHAR(200),
    review_reason        VARCHAR(200),
    reference            VARCHAR(200),
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (captured_amount <= amount),
    CHECK (refunded_amount <= captured_amount)
);

CREATE INDEX idx_payments_created ON payments (created_at);
CREATE INDEX idx_payments_merchant ON payments (merchant_account_id);

CREATE TABLE payment_events (
    id           BIGSERIAL PRIMARY KEY,
    payment_id   UUID        NOT NULL REFERENCES payments (id),
    from_status  VARCHAR(24),
    to_status    VARCHAR(24) NOT NULL,
    detail       VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_events_payment ON payment_events (payment_id);

-- System clearing accounts: the receivable from the card network. They carry
-- no balance constraint because they go negative until the network settles.
INSERT INTO accounts (id, code, name, currency, flags) VALUES
    ('00000000-0000-0000-0000-00000000c001', 'sys.card_clearing.USD', 'Card network clearing (USD)', 'USD', 'NONE'),
    ('00000000-0000-0000-0000-00000000c002', 'sys.card_clearing.EUR', 'Card network clearing (EUR)', 'EUR', 'NONE');
