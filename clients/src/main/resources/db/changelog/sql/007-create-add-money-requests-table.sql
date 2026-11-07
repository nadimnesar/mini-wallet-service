--liquibase formatted sql

--changeset miniwallet:007-create-add-money-requests-table
CREATE TABLE add_money_requests
(
    id             UUID        NOT NULL,
    wallet_id      UUID        NOT NULL,
    amount         BIGINT      NOT NULL,
    gateway_ref    UUID        NOT NULL,
    payment_method VARCHAR(16) NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts       INT         NOT NULL DEFAULT 0,
    transaction_id UUID,
    error_code     TEXT,
    error_message  TEXT,
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_add_money_requests PRIMARY KEY (id),
    CONSTRAINT fk_add_money_requests_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (wallet_id),
    CONSTRAINT fk_add_money_requests_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT ck_add_money_requests_amount CHECK (amount > 0),
    CONSTRAINT ck_add_money_requests_payment_method CHECK (payment_method IN ('VISA', 'MASTERCARD')),
    CONSTRAINT ck_add_money_requests_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_add_money_requests_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_add_money_requests_error_pair CHECK ((error_code IS NULL) = (error_message IS NULL)),
    CONSTRAINT ck_add_money_requests_outcome CHECK (
        (status = 'COMPLETED' AND transaction_id IS NOT NULL)
            OR (status = 'FAILED' AND transaction_id IS NULL AND error_code IS NOT NULL)
            OR (status = 'PENDING' AND transaction_id IS NULL))
);
CREATE UNIQUE INDEX uq_add_money_requests_open_gateway_ref ON add_money_requests (gateway_ref) WHERE status <> 'FAILED';
CREATE INDEX ix_add_money_requests_pending ON add_money_requests (created_at) WHERE status = 'PENDING';
--rollback DROP TABLE add_money_requests;
