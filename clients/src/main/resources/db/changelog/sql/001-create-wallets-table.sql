--liquibase formatted sql

--changeset miniwallet:001-create-wallets-table
CREATE TABLE wallets
(
    id         UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    phone      VARCHAR(11) NOT NULL,
    currency   CHAR(3)     NOT NULL DEFAULT 'BDT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_wallets PRIMARY KEY (id),
    CONSTRAINT uq_wallets_user_id UNIQUE (user_id),
    CONSTRAINT uq_wallets_phone UNIQUE (phone),
    CONSTRAINT ck_wallets_currency_bdt CHECK (currency = 'BDT'),
    CONSTRAINT ck_wallets_phone_format CHECK (phone ~ '^01[0-9]{9}$')
);
--rollback DROP TABLE wallets;
