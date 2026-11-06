--liquibase formatted sql

--changeset miniwallet:003-create-idempotency-keys-table
--comment: Outcome of each Idempotency-Key. The key is claimed, the work done and the outcome stored in ONE transaction, so a concurrent duplicate waits on the uncommitted key and then replays the stored outcome (exactly one execution).
CREATE TABLE idempotency_keys
(
    idempotency_key TEXT        NOT NULL,
    operation       VARCHAR(32) NOT NULL,
    -- SHA-256 (hex) of the operation and its full request; a different hash means the key was reused.
    request_hash    CHAR(64)    NOT NULL,
    -- Success: id of the created resource (the ledger entry for ADD_MONEY).
    resource_id     UUID,
    -- Deterministic failure: the error returned to the client. Transient failures are never stored.
    error_code      TEXT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (idempotency_key),
    CONSTRAINT ck_idempotency_keys_key_length CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    CONSTRAINT ck_idempotency_keys_operation CHECK (operation IN ('ADD_MONEY', 'TRANSFER')),
    CONSTRAINT ck_idempotency_keys_single_outcome CHECK (resource_id IS NULL OR error_code IS NULL),
    CONSTRAINT ck_idempotency_keys_error_pair CHECK ((error_code IS NULL) = (error_message IS NULL)),
    CONSTRAINT ck_idempotency_keys_completed CHECK (
        (completed_at IS NULL) = (resource_id IS NULL AND error_code IS NULL))
);
--rollback DROP TABLE idempotency_keys;
