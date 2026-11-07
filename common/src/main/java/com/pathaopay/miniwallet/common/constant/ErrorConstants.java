package com.pathaopay.miniwallet.common.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Error codes and messages returned in the {@code error} body. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ErrorConstants {

    /** Machine-readable error codes returned in {@code error.code}. */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class ErrorCode {
        public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
        public static final String MISSING_IDEMPOTENCY_KEY = "MISSING_IDEMPOTENCY_KEY";
        public static final String MISSING_HEADER = "MISSING_HEADER";
        public static final String INVALID_PAYLOAD = "INVALID_PAYLOAD";
        public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
        public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
        public static final String INVALID_PHONE_NUMBER = "INVALID_PHONE_NUMBER";
        public static final String WALLET_NOT_FOUND = "WALLET_NOT_FOUND";
        public static final String TRANSACTION_NOT_FOUND = "TRANSACTION_NOT_FOUND";
        public static final String DUPLICATE_WALLET = "DUPLICATE_WALLET";
        public static final String DUPLICATE_PHONE = "DUPLICATE_PHONE";
        public static final String DUPLICATE_GATEWAY_REF = "DUPLICATE_GATEWAY_REF";
        public static final String PAYMENT_DECLINED = "PAYMENT_DECLINED";
        public static final String PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";
        public static final String PAYMENT_MISMATCH = "PAYMENT_MISMATCH";
        public static final String PAYMENT_GATEWAY_ERROR = "PAYMENT_GATEWAY_ERROR";
        public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
        public static final String SAME_WALLET_TRANSFER = "SAME_WALLET_TRANSFER";
        public static final String SYSTEM_WALLET_NOT_ALLOWED = "SYSTEM_WALLET_NOT_ALLOWED";
        public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
        public static final String INVALID_DATE_RANGE = "INVALID_DATE_RANGE";
        public static final String NOT_FOUND = "NOT_FOUND";
        public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
        public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
        public static final String NOT_ACCEPTABLE = "NOT_ACCEPTABLE";
        public static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";
    }

    /** Human-readable error messages returned in {@code error.message}. */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class ErrorMessage {
        public static final String VALIDATION_FAILED = "Validation failed";
        public static final String INVALID_PARAMETER_OR_HEADER = "Invalid parameter or header value";
        public static final String CONSTRAINT_VIOLATION = "Constraint violation";
        public static final String IDEMPOTENCY_KEY_REQUIRED = AppConstants.IDEMPOTENCY_KEY_HEADER + " header is required";
        /** Format with the header name. */
        public static final String MISSING_HEADER = "Required header '%s' is missing";
        public static final String MALFORMED_JSON = "Malformed or unreadable JSON request body";
        /** Format with the JSON field path, e.g. user_id. */
        public static final String INVALID_FIELD_VALUE = "Invalid value for field: %s";
        public static final String INVALID_ARGUMENT_PREFIX = "Invalid argument for parameter: ";
        public static final String WALLET_NOT_FOUND_PREFIX = "Wallet not found with phone: ";
        public static final String RESERVED_USER_ID = "user_id is reserved for system accounts and cannot be used";
        public static final String DUPLICATE_WALLET_PREFIX = "A wallet already exists for user_id: ";
        public static final String DUPLICATE_PHONE_PREFIX = "A wallet already exists for phone: ";
        public static final String TRANSACTION_NOT_FOUND_PREFIX = "Transaction not found with id: ";
        /** Format with the sender phone. */
        public static final String INSUFFICIENT_BALANCE = "Wallet %s does not have enough balance for this transfer and its fee.";
        /** Format with the minimum amount in poisha and in BDT. */
        public static final String AMOUNT_BELOW_MIN = "amount must be at least %d poisha (%s BDT)";
        /** Format with the maximum amount in poisha and in BDT. */
        public static final String AMOUNT_ABOVE_MAX = "amount must be at most %d poisha (%s BDT)";
        /** Format with the fee, the single-transaction limit and the resulting maximum amount, each in poisha and BDT. */
        public static final String AMOUNT_PLUS_FEE_ABOVE_MAX = "amount plus the transfer fee of %d poisha (%s BDT) must be at most %d poisha (%s BDT), so amount must be at most %d poisha (%s BDT)";
        public static final String SAME_WALLET_TRANSFER = "Sender and receiver must be different wallets.";
        /** Format with the wallet phone and its account type. */
        public static final String SYSTEM_WALLET_NOT_ALLOWED =
                "Wallet %s is a %s ledger account, not a customer wallet, and cannot take part in this operation.";
        public static final String INVALID_DATE_RANGE = "from must not be after to";
        public static final String DUPLICATE_GATEWAY_REF_PREFIX = "This gateway_ref has already been used: ";
        public static final String IDEMPOTENCY_KEY_REUSED =
                AppConstants.IDEMPOTENCY_KEY_HEADER + " has already been used with a different request";
        /** Format with network, gateway_ref, status and decline reason. */
        public static final String PAYMENT_DECLINED = "%s payment %s was not captured (status: %s, reason: %s)";
        /** Format with network and gateway_ref. */
        public static final String PAYMENT_NOT_FOUND = "%s gateway has no payment with gateway_ref %s";
        /** Format with gateway_ref, requested and confirmed details. */
        public static final String PAYMENT_MISMATCH = "Gateway confirmation for %s does not match the request "
                + "(requested %s, confirmed %s)";
        /** Format with network. */
        public static final String PAYMENT_GATEWAY_ERROR = "%s payment gateway is unavailable. Please retry later.";
        public static final String RESOURCE_NOT_FOUND = "Resource not found";
        public static final String INVALID_PHONE_NUMBER = AppConstants.PHONE_FORMAT;
        public static final String CUSTOMER_PHONE_FORMAT = AppConstants.CUSTOMER_PHONE_FORMAT;
        /** Format with the HTTP method. */
        public static final String METHOD_NOT_SUPPORTED = "HTTP method %s is not supported for this endpoint";
        public static final String UNSUPPORTED_MEDIA_TYPE = "Content-Type is not supported, use application/json";
        public static final String NOT_ACCEPTABLE = "This API only produces application/json";
        public static final String UNEXPECTED_ERROR = "An unexpected error occurred. Please try again later.";
        public static final String UNHANDLED_EXCEPTION_LOG = "Unhandled server exception encountered: ";
    }
}
