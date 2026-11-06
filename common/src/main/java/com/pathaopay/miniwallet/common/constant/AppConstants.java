package com.pathaopay.miniwallet.common.constant;

public final class AppConstants {

    private AppConstants() {
    }

    /** HTTP header names. */
    public static final class Headers {
        public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

        private Headers() {
        }
    }

    /** Money rules. All amounts are integer poisha (1 BDT = 100 poisha). */
    public static final class Money {
        public static final long POISHA_PER_BDT = 100;
        public static final long MIN_AMOUNT_POISHA = 100;
        public static final long MAX_AMOUNT_POISHA = 2_500_000;

        private Money() {
        }
    }

    /** Bangladeshi mobile number formats. */
    public static final class Phone {
        /** Prefix that turns the 10-digit subscriber number (1XXXXXXXXX) into the local form. */
        public static final String LOCAL_PREFIX = "0";
        /**
         * Accepted input: 1XXXXXXXXX, 01XXXXXXXXX, 8801XXXXXXXXX or +8801XXXXXXXXX.
         * Group 1 captures the 10-digit subscriber number (1XXXXXXXXX).
         */
        public static final String ACCEPTED_PATTERN = "^(?:\\+?880|0)?(1\\d{9})$";

        private Phone() {
        }
    }

    /** Request validation limits, patterns and messages. */
    public static final class Validation {
        public static final int ID_MIN_LENGTH = 1;
        public static final int ID_MAX_LENGTH = 64;
        public static final int GATEWAY_REF_MAX_LENGTH = 128;
        public static final int NOTE_MAX_LENGTH = 255;

        public static final String ID_PATTERN = "^[a-zA-Z0-9_.-]+$";
        public static final String GATEWAY_REF_PATTERN = "^[a-zA-Z0-9_-]+$";

        private static final String ID_CHARS = " must contain only alphanumeric characters, underscores, dashes, or dots";
        private static final String ID_LENGTH = " must be between " + ID_MIN_LENGTH + " and " + ID_MAX_LENGTH + " characters";

        public static final String USER_ID_REQUIRED = "user_id is required";

        public static final String PHONE_REQUIRED = "phone is required";
        public static final String PHONE_FORMAT = "phone must be a Bangladeshi mobile number in one of the formats "
                + "1XXXXXXXXX, 01XXXXXXXXX, 8801XXXXXXXXX or +8801XXXXXXXXX";

        public static final String WALLET_ID_REQUIRED = "wallet_id is required";
        public static final String WALLET_ID_SIZE = "wallet_id" + ID_LENGTH;
        public static final String WALLET_ID_PATTERN = "wallet_id" + ID_CHARS;

        public static final String FROM_WALLET_ID_REQUIRED = "from_wallet_id is required";
        public static final String FROM_WALLET_ID_SIZE = "from_wallet_id" + ID_LENGTH;
        public static final String FROM_WALLET_ID_PATTERN = "from_wallet_id" + ID_CHARS;

        public static final String TO_WALLET_ID_REQUIRED = "to_wallet_id is required";
        public static final String TO_WALLET_ID_SIZE = "to_wallet_id" + ID_LENGTH;
        public static final String TO_WALLET_ID_PATTERN = "to_wallet_id" + ID_CHARS;

        public static final String GATEWAY_REF_REQUIRED = "gateway_ref is required";
        public static final String GATEWAY_REF_SIZE =
                "gateway_ref must be between " + ID_MIN_LENGTH + " and " + GATEWAY_REF_MAX_LENGTH + " characters";
        public static final String GATEWAY_REF_PATTERN_MESSAGE =
                "gateway_ref must contain only alphanumeric characters, underscores, or hyphens";

        public static final String AMOUNT_REQUIRED = "amount is required";
        public static final String AMOUNT_MIN = "amount must be at least " + Money.MIN_AMOUNT_POISHA
                + " poisha (" + Money.MIN_AMOUNT_POISHA / Money.POISHA_PER_BDT + " BDT)";
        public static final String AMOUNT_MAX = "amount must be at most " + Money.MAX_AMOUNT_POISHA
                + " poisha (" + Money.MAX_AMOUNT_POISHA / Money.POISHA_PER_BDT + " BDT)";

        public static final String NOTE_SIZE = "note must not exceed " + NOTE_MAX_LENGTH + " characters";

        private Validation() {
        }
    }

    /** Machine-readable error codes returned in {@code error.code}. */
    public static final class ErrorCodes {
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
        public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
        public static final String SAME_WALLET_TRANSFER = "SAME_WALLET_TRANSFER";
        public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
        public static final String NOT_FOUND = "NOT_FOUND";
        public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
        public static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";

        private ErrorCodes() {
        }
    }

    /** Human-readable error messages returned in {@code error.message}. */
    public static final class ErrorMessages {
        public static final String VALIDATION_FAILED = "Validation failed";
        public static final String INVALID_PARAMETER_OR_HEADER = "Invalid parameter or header value";
        public static final String CONSTRAINT_VIOLATION = "Constraint violation";
        public static final String IDEMPOTENCY_KEY_REQUIRED = Headers.IDEMPOTENCY_KEY + " header is required";
        /** Format with the header name. */
        public static final String MISSING_HEADER = "Required header '%s' is missing";
        public static final String MALFORMED_JSON = "Malformed or unreadable JSON request body";
        public static final String INVALID_ARGUMENT_PREFIX = "Invalid argument for parameter: ";
        public static final String WALLET_NOT_FOUND_PREFIX = "Wallet not found with id: ";
        public static final String DUPLICATE_WALLET_PREFIX = "A wallet already exists for user_id: ";
        public static final String DUPLICATE_PHONE_PREFIX = "A wallet already exists for phone: ";
        public static final String TRANSACTION_NOT_FOUND_PREFIX = "Transaction not found with id: ";
        public static final String RESOURCE_NOT_FOUND = "Resource not found";
        public static final String INVALID_PHONE_NUMBER = Validation.PHONE_FORMAT;
        /** Format with the HTTP method. */
        public static final String METHOD_NOT_SUPPORTED = "HTTP method %s is not supported for this endpoint";
        public static final String UNEXPECTED_ERROR = "An unexpected error occurred. Please try again later.";
        public static final String UNHANDLED_EXCEPTION_LOG = "Unhandled server exception encountered: ";

        private ErrorMessages() {
        }
    }

    /** Read/write datasource property prefixes and bean names. */
    public static final class Database {
        public static final String WRITE_PROPERTIES_PREFIX = "miniwallet.datasource.write";
        public static final String READ_PROPERTIES_PREFIX = "miniwallet.datasource.read";
        public static final String WRITE_BEAN = "writeDataSource";
        public static final String READ_BEAN = "readDataSource";

        private Database() {
        }
    }
}
