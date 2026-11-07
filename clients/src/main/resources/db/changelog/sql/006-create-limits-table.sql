--liquibase formatted sql

--changeset miniwallet:006-create-limits-table
CREATE TABLE limits
(
    transaction_type VARCHAR(16) NOT NULL,
    min_amount       BIGINT      NOT NULL,
    max_amount       BIGINT      NOT NULL,
    CONSTRAINT pk_limits PRIMARY KEY (transaction_type),
    CONSTRAINT ck_limits_transaction_type CHECK (transaction_type IN ('ADD_MONEY', 'TRANSFER')),
    CONSTRAINT ck_limits_min_amount CHECK (min_amount > 0),
    CONSTRAINT ck_limits_range CHECK (max_amount >= min_amount)
);
--rollback DROP TABLE limits;

--changeset miniwallet:006-seed-limits
INSERT INTO limits (transaction_type, min_amount, max_amount)
VALUES ('ADD_MONEY', 100, 2500000),
       ('TRANSFER', 100, 2500000);
--rollback DELETE FROM limits;
