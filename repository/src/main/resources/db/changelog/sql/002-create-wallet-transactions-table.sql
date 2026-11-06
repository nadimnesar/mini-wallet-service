--liquibase formatted sql

--changeset miniwallet:002-create-wallet-transactions-table
--comment: Append-only ledger and the single source of truth for balances. A wallet's balance is the balance_after of its latest entry (0 if none).
CREATE TABLE wallet_transactions
(
    id                     UUID        NOT NULL,
    wallet_id              UUID        NOT NULL,
    -- 1, 2, 3, ... per wallet: the position of this entry in the wallet's chain.
    entry_no               BIGINT      NOT NULL,
    previous_entry_no      BIGINT GENERATED ALWAYS AS (NULLIF(entry_no - 1, 0)) STORED,
    type                   VARCHAR(16) NOT NULL,
    amount                 BIGINT      NOT NULL,
    balance_before         BIGINT      NOT NULL,
    balance_after          BIGINT      NOT NULL,
    counterparty_wallet_id UUID,
    -- gateway_ref for ADD_MONEY; the transfer id (shared by both legs) for transfers.
    reference_id           TEXT        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_wallet_transactions PRIMARY KEY (id),
    CONSTRAINT fk_wallet_transactions_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_wallet_transactions_counterparty FOREIGN KEY (counterparty_wallet_id) REFERENCES wallets (id),

    -- Chain integrity: at most one entry per position (no forks) ...
    CONSTRAINT uq_wallet_transactions_entry UNIQUE (wallet_id, entry_no),
    CONSTRAINT uq_wallet_transactions_entry_balance UNIQUE (wallet_id, entry_no, balance_after),
    -- ... every entry continues from the previous entry's balance (no gaps, no jumps) ...
    CONSTRAINT fk_wallet_transactions_previous FOREIGN KEY (wallet_id, previous_entry_no, balance_before)
        REFERENCES wallet_transactions (wallet_id, entry_no, balance_after),
    -- ... and the first entry starts from 0: the initial balance of every wallet is 0.
    CONSTRAINT ck_wallet_transactions_entry_no CHECK (entry_no >= 1),
    CONSTRAINT ck_wallet_transactions_first_entry_from_zero CHECK (entry_no > 1 OR balance_before = 0),

    -- Money rules (amounts in poisha).
    CONSTRAINT ck_wallet_transactions_type CHECK (type IN ('ADD_MONEY', 'TRANSFER_IN', 'TRANSFER_OUT')),
    CONSTRAINT ck_wallet_transactions_amount CHECK (amount BETWEEN 100 AND 2500000),
    CONSTRAINT ck_wallet_transactions_non_negative CHECK (balance_after >= 0),
    CONSTRAINT ck_wallet_transactions_arithmetic CHECK (
        (type IN ('ADD_MONEY', 'TRANSFER_IN') AND balance_after = balance_before + amount)
            OR (type = 'TRANSFER_OUT' AND balance_after = balance_before - amount)),
    CONSTRAINT ck_wallet_transactions_counterparty CHECK (
        (type = 'ADD_MONEY' AND counterparty_wallet_id IS NULL)
            OR (type <> 'ADD_MONEY' AND counterparty_wallet_id IS NOT NULL
                AND counterparty_wallet_id <> wallet_id))
);

-- A gateway reference can credit a wallet only once.
CREATE UNIQUE INDEX uq_wallet_transactions_gateway_ref
    ON wallet_transactions (reference_id) WHERE type = 'ADD_MONEY';

CREATE INDEX ix_wallet_transactions_wallet_created_at
    ON wallet_transactions (wallet_id, created_at DESC);
--rollback DROP TABLE wallet_transactions;
