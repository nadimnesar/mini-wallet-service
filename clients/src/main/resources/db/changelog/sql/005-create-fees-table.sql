--liquibase formatted sql

--changeset miniwallet:005-create-fees-table
CREATE TABLE fees
(
    transaction_type VARCHAR(16) NOT NULL,
    amount           BIGINT      NOT NULL,
    CONSTRAINT pk_fees PRIMARY KEY (transaction_type),
    CONSTRAINT ck_fees_transaction_type CHECK (transaction_type IN ('TRANSFER')),
    CONSTRAINT ck_fees_amount CHECK (amount > 0)
);
--rollback DROP TABLE fees;

--changeset miniwallet:005-seed-transfer-fee
INSERT INTO fees (transaction_type, amount)
VALUES ('TRANSFER', 500);
--rollback DELETE FROM fees WHERE transaction_type = 'TRANSFER';
