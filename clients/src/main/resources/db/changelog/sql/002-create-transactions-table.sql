--liquibase formatted sql

--changeset miniwallet:002-create-transactions-table splitStatements:false
CREATE TABLE transactions
(
    id             UUID        NOT NULL,
    type           VARCHAR(16) NOT NULL,
    amount         BIGINT      NOT NULL,
    gateway_ref    UUID,
    payment_method VARCHAR(16),
    note           VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT uq_transactions_gateway_ref UNIQUE (gateway_ref),
    CONSTRAINT ck_transactions_type CHECK (type IN ('ADD_MONEY', 'TRANSFER')),
    CONSTRAINT ck_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_transactions_note CHECK (note IS NULL OR (type = 'TRANSFER' AND length(note) >= 1)),
    CONSTRAINT ck_transactions_gateway CHECK (
        (type = 'ADD_MONEY' AND gateway_ref IS NOT NULL AND payment_method IS NOT NULL
            AND payment_method IN ('VISA', 'MASTERCARD'))
            OR (type <> 'ADD_MONEY' AND gateway_ref IS NULL AND payment_method IS NULL))
);

-- ledger_reject_change(): trigger function that makes a table append-only.
--   Why:  the ledger is the source of truth for every balance, so a posted row must never be edited or removed.
--         Service code never updates or deletes, but this stops a bug, a manual SQL fix or a bad migration too.
--   When: it is attached BEFORE UPDATE OR DELETE, FOR EACH ROW, to transactions (below) and to ledger_entries
--         (changeset 003). It runs before the change touches the row and always raises, so the statement fails
--         and rolls back its transaction. INSERT is not affected.
--   How:  RAISE EXCEPTION with SQLSTATE 23000 (integrity constraint violation), naming the table via TG_TABLE_NAME.
CREATE FUNCTION ledger_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '23000';
END;
$$;

-- Blocks every UPDATE and DELETE on transactions.
CREATE TRIGGER trg_transactions_append_only
    BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
--rollback DROP TABLE transactions;
--rollback DROP FUNCTION ledger_reject_change();
