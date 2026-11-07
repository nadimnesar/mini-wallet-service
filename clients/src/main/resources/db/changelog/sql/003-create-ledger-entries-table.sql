--liquibase formatted sql

--changeset miniwallet:003-create-ledger-entries-table splitStatements:false
CREATE TABLE ledger_entries
(
    id                     UUID        NOT NULL,
    wallet_id              UUID        NOT NULL,
    wallet_type            VARCHAR(16) NOT NULL,
    entry_no               BIGINT      NOT NULL,
    previous_entry_no      BIGINT GENERATED ALWAYS AS (NULLIF(entry_no - 1, 0)) STORED,
    type                   VARCHAR(16) NOT NULL,
    direction              VARCHAR(6)  NOT NULL,
    amount                 BIGINT      NOT NULL,
    balance_before         BIGINT      NOT NULL,
    balance_after          BIGINT      NOT NULL,
    counterparty_wallet_id UUID        NOT NULL,
    transaction_id         UUID        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entries_wallet FOREIGN KEY (wallet_id, wallet_type) REFERENCES wallets (wallet_id, type),
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_ledger_entries_counterparty FOREIGN KEY (counterparty_wallet_id) REFERENCES wallets (wallet_id),
    CONSTRAINT uq_ledger_entries_entry UNIQUE (wallet_id, entry_no),
    CONSTRAINT uq_ledger_entries_entry_balance UNIQUE (wallet_id, entry_no, balance_after),
    CONSTRAINT fk_ledger_entries_previous FOREIGN KEY (wallet_id, previous_entry_no, balance_before)
        REFERENCES ledger_entries (wallet_id, entry_no, balance_after),
    CONSTRAINT ck_ledger_entries_entry_no CHECK (entry_no >= 1),
    CONSTRAINT ck_ledger_entries_first_entry_from_zero CHECK (entry_no > 1 OR balance_before = 0),
    CONSTRAINT ck_ledger_entries_type CHECK (
        type IN ('ADD_MONEY', 'GATEWAY_CLEARING', 'TRANSFER_IN', 'TRANSFER_OUT', 'FEE_IN', 'FEE_OUT')),
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_type_direction CHECK (
        (type IN ('ADD_MONEY', 'TRANSFER_IN', 'FEE_IN') AND direction = 'CREDIT')
            OR (type IN ('GATEWAY_CLEARING', 'TRANSFER_OUT', 'FEE_OUT') AND direction = 'DEBIT')),
    CONSTRAINT ck_ledger_entries_type_wallet_type CHECK (
        (type IN ('ADD_MONEY', 'TRANSFER_IN', 'TRANSFER_OUT', 'FEE_OUT') AND wallet_type = 'LIABILITY')
            OR (type = 'FEE_IN' AND wallet_type = 'REVENUE')
            OR (type = 'GATEWAY_CLEARING' AND wallet_type = 'ASSET')),
    CONSTRAINT ck_ledger_entries_amount CHECK (amount > 0),
    CONSTRAINT ck_ledger_entries_non_negative CHECK (balance_after >= 0),
    CONSTRAINT ck_ledger_entries_arithmetic CHECK (
        balance_after = balance_before + CASE
            WHEN (direction = 'DEBIT') = (wallet_type IN ('ASSET', 'EXPENSE')) THEN amount
            ELSE -amount END),
    CONSTRAINT ck_ledger_entries_counterparty CHECK (counterparty_wallet_id <> wallet_id)
);

CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (transaction_id);

CREATE INDEX ix_ledger_entries_wallet_created_at
    ON ledger_entries (wallet_id, created_at DESC);

CREATE FUNCTION ledger_assert_transaction_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    tx_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'transactions' THEN
        tx_id := NEW.id;
    ELSE
        tx_id := NEW.transaction_id;
    END IF;
    IF (SELECT COUNT(*) < 2 OR SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0
        FROM ledger_entries
        WHERE transaction_id = tx_id) THEN
        RAISE EXCEPTION 'transaction % needs at least two entries and debits must equal credits', tx_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_transaction_balanced();

CREATE CONSTRAINT TRIGGER trg_transactions_balanced
    AFTER INSERT ON transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_transaction_balanced();

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
--rollback DROP TABLE ledger_entries;
--rollback DROP FUNCTION ledger_assert_transaction_balanced();
