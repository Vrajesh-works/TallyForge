-- Daily reconciliation results.
CREATE TABLE reconciliation_runs (
    id                UUID PRIMARY KEY,
    trigger           VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('OK', 'MISMATCH')),
    accounts_checked  INTEGER      NOT NULL,
    transfers_checked INTEGER      NOT NULL,
    payments_checked  INTEGER      NOT NULL,
    mismatch_count    INTEGER      NOT NULL,
    started_at        TIMESTAMPTZ  NOT NULL,
    finished_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE reconciliation_mismatches (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID         NOT NULL REFERENCES reconciliation_runs (id),
    check_name  VARCHAR(64)  NOT NULL,
    subject     VARCHAR(200) NOT NULL,
    expected    BIGINT       NOT NULL,
    actual      BIGINT       NOT NULL
);

CREATE INDEX idx_recon_mismatch_run ON reconciliation_mismatches (run_id);
