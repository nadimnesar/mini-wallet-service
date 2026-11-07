package com.pathaopay.miniwallet.common.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Database-only constants: datasource settings, table names and column names. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DbConstants {

    public static final String WRITE_PROPERTIES_PREFIX = "miniwallet.datasource.write";
    public static final String READ_PROPERTIES_PREFIX = "miniwallet.datasource.read";
    public static final String WRITE_BEAN = "writeDataSource";
    public static final String READ_BEAN = "readDataSource";

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
        public static final String CREATED_AT = "created_at";
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
