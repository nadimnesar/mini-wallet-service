package com.pathaopay.miniwallet.common.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Database-only constants: table names and column names. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DbConstants {


    /** Table {@code wallets}. */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Wallets {
        public static final String TABLE = "wallets";
        public static final String WALLET_ID = "wallet_id";
        public static final String USER_ID = "user_id";
        public static final String PHONE = "phone";
        public static final String CURRENCY = "currency";
        public static final String TYPE = "type";
        public static final String CREATED_AT = "created_at";
    }

    /** Table {@code transactions}: one append-only row per business operation, grouping its ledger entries. */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Transactions {
        public static final String TABLE = "transactions";
        public static final String ID = "id";
        public static final String TYPE = "type";
        public static final String AMOUNT = "amount";
        public static final String GATEWAY_REF = "gateway_ref";
        public static final String PAYMENT_METHOD = "payment_method";
        public static final String NOTE = "note";
        public static final String CREATED_AT = "created_at";
    }

    /** Table {@code fees}: the fee charged per transaction type, seeded by Liquibase. */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Fees {
        public static final String TABLE = "fees";
        public static final String TRANSACTION_TYPE = "transaction_type";
        public static final String AMOUNT = "amount";
    }

    /** Table {@code limits}: the smallest and largest amount per transaction type, seeded by Liquibase. */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Limits {
        public static final String TABLE = "limits";
        public static final String TRANSACTION_TYPE = "transaction_type";
        public static final String MIN_AMOUNT = "min_amount";
        public static final String MAX_AMOUNT = "max_amount";
    }

    /** Table {@code ledger_entries}, the append-only ledger. */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class LedgerEntries {
        public static final String TABLE = "ledger_entries";
        public static final String ID = "id";
        public static final String WALLET_ID = "wallet_id";
        public static final String WALLET_TYPE = "wallet_type";
        public static final String ENTRY_NO = "entry_no";
        public static final String PREVIOUS_ENTRY_NO = "previous_entry_no";
        public static final String TYPE = "type";
        public static final String DIRECTION = "direction";
        public static final String AMOUNT = "amount";
        public static final String BALANCE_BEFORE = "balance_before";
        public static final String BALANCE_AFTER = "balance_after";
        public static final String COUNTERPARTY_WALLET_ID = "counterparty_wallet_id";
        public static final String TRANSACTION_ID = "transaction_id";
        public static final String CREATED_AT = "created_at";
    }

    /** Table {@code add_money_requests}: one mutable row per accepted add-money request, the work item of the queue. */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class AddMoneyRequests {
        public static final String TABLE = "add_money_requests";
        public static final String ID = "id";
        public static final String WALLET_ID = "wallet_id";
        public static final String AMOUNT = "amount";
        public static final String GATEWAY_REF = "gateway_ref";
        public static final String PAYMENT_METHOD = "payment_method";
        public static final String STATUS = "status";
        public static final String ATTEMPTS = "attempts";
        public static final String TRANSACTION_ID = "transaction_id";
        public static final String ERROR_CODE = "error_code";
        public static final String ERROR_MESSAGE = "error_message";
        public static final String PUBLISHED_AT = "published_at";
        public static final String CREATED_AT = "created_at";
        public static final String UPDATED_AT = "updated_at";
    }

    /** Table {@code idempotency_keys}. */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class IdempotencyKeys {
        public static final String TABLE = "idempotency_keys";
        public static final String IDEMPOTENCY_KEY = "idempotency_key";
        public static final String OPERATION = "operation";
        public static final String REQUEST_HASH = "request_hash";
        public static final String RESOURCE_ID = "resource_id";
        public static final String ERROR_CODE = "error_code";
        public static final String ERROR_MESSAGE = "error_message";
        public static final String CREATED_AT = "created_at";
        public static final String COMPLETED_AT = "completed_at";
    }
}
