--liquibase formatted sql

--changeset miniwallet:002-add-unique-phone-to-wallets
--comment: A phone number (stored normalized as 01XXXXXXXXX) can belong to only one wallet.
ALTER TABLE wallets ADD CONSTRAINT uq_wallets_phone UNIQUE (phone);
--rollback ALTER TABLE wallets DROP CONSTRAINT uq_wallets_phone;
