-- Double-entry ledger core: accounts, transfers and journal entries.
-- All amounts are minor units (cents) stored as BIGINT.

CREATE TABLE accounts (
    id               UUID PRIMARY KEY,
    code             VARCHAR(64)  NOT NULL UNIQUE,
    name             VARCHAR(200) NOT NULL,
    currency         VARCHAR(3)   NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    flags            VARCHAR(64)  NOT NULL DEFAULT 'NONE',
    debits_pending   BIGINT       NOT NULL DEFAULT 0 CHECK (debits_pending >= 0),
    debits_posted    BIGINT       NOT NULL DEFAULT 0 CHECK (debits_posted >= 0),
    credits_pending  BIGINT       NOT NULL DEFAULT 0 CHECK (credits_pending >= 0),
    credits_posted   BIGINT       NOT NULL DEFAULT 0 CHECK (credits_posted >= 0),
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
    id                 UUID PRIMARY KEY,
    debit_account_id   UUID        NOT NULL REFERENCES accounts (id),
    credit_account_id  UUID        NOT NULL REFERENCES accounts (id),
    amount             BIGINT      NOT NULL CHECK (amount > 0),
    posted_amount      BIGINT      NOT NULL DEFAULT 0 CHECK (posted_amount >= 0),
    refunded_amount    BIGINT      NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    currency           VARCHAR(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    kind               VARCHAR(16) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    refund_of          UUID REFERENCES transfers (id),
    hold_reason        VARCHAR(200),
    reference          VARCHAR(200),
    version            BIGINT      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (debit_account_id <> credit_account_id),
    CHECK (posted_amount <= amount),
    CHECK (refunded_amount <= posted_amount)
);

CREATE INDEX idx_transfers_debit ON transfers (debit_account_id, created_at);
CREATE INDEX idx_transfers_credit ON transfers (credit_account_id, created_at);
CREATE INDEX idx_transfers_created ON transfers (created_at);
CREATE INDEX idx_transfers_status ON transfers (status);

-- One row per balance movement. Amount is signed: releasing a hold writes a
-- negative PENDING line, so SUM(amount) per account/side/phase equals the
-- matching balance column on the account.
CREATE TABLE journal_entries (
    id           BIGSERIAL PRIMARY KEY,
    transfer_id  UUID        NOT NULL REFERENCES transfers (id),
    account_id   UUID        NOT NULL REFERENCES accounts (id),
    side         VARCHAR(6)  NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    phase        VARCHAR(7)  NOT NULL CHECK (phase IN ('PENDING', 'POSTED')),
    amount       BIGINT      NOT NULL,
    currency     VARCHAR(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_journal_account ON journal_entries (account_id);
CREATE INDEX idx_journal_transfer ON journal_entries (transfer_id);
