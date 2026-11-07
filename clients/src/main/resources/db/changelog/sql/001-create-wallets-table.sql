--liquibase formatted sql

--changeset miniwallet:001-create-wallets-table
CREATE TABLE wallets
(
    wallet_id  UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    phone      VARCHAR(11) NOT NULL,
    currency   CHAR(3)     NOT NULL DEFAULT 'BDT',
    type       VARCHAR(16) NOT NULL DEFAULT 'LIABILITY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_wallets PRIMARY KEY (wallet_id),
    CONSTRAINT uq_wallets_id_type UNIQUE (wallet_id, type),
    CONSTRAINT uq_wallets_phone UNIQUE (phone),
    CONSTRAINT ck_wallets_currency_bdt CHECK (currency = 'BDT'),
    CONSTRAINT ck_wallets_type CHECK (type IN ('ASSET', 'EXPENSE', 'REVENUE', 'EQUITY', 'LIABILITY')),
    CONSTRAINT ck_wallets_phone_format CHECK (phone ~ '^01[0-9]{9}$')
);
--rollback DROP TABLE wallets;

--changeset miniwallet:001-seed-system-wallets
INSERT INTO wallets (wallet_id, user_id, phone, type)
VALUES ('00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000002', '01000000001', 'REVENUE'),
       ('00000000-0000-7000-8000-000000000003', '00000000-0000-7000-8000-000000000002', '01000000003', 'ASSET');
--rollback DELETE FROM wallets WHERE user_id = '00000000-0000-7000-8000-000000000002';
