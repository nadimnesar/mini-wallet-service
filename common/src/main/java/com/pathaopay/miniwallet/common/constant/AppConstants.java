package com.pathaopay.miniwallet.common.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Application-wide constants: HTTP headers, money rules, phone formats and request validation. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AppConstants {

    // ---- HTTP headers
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    // ---- Money: all amounts are integer poisha (1 BDT = 100 poisha)
    public static final long POISHA_PER_BDT = 100;
    public static final long MIN_AMOUNT_POISHA = 100;
    public static final long MAX_AMOUNT_POISHA = 2_500_000;
    /** ISO 4217 code of the only supported currency. */
    public static final String CURRENCY_CODE = "BDT";

    // ---- Bangladeshi mobile number formats
    /** Prefix that turns the 10-digit subscriber number (1XXXXXXXXX) into the local form. */
    public static final String PHONE_LOCAL_PREFIX = "0";
    /**
     * Accepted input: 1XXXXXXXXX, 01XXXXXXXXX, 8801XXXXXXXXX or +8801XXXXXXXXX.
     * Group 1 captures the 10-digit subscriber number (1XXXXXXXXX).
     */
    public static final String PHONE_ACCEPTED_PATTERN = "^(?:\\+?880|0)?(1\\d{9})$";

    // ---- Request validation limits, patterns and messages
    public static final int ID_MIN_LENGTH = 1;
    public static final int ID_MAX_LENGTH = 64;
    public static final int NOTE_MAX_LENGTH = 255;

    public static final String ID_PATTERN = "^[a-zA-Z0-9_.-]+$";

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

    public static final String AMOUNT_REQUIRED = "amount is required";
    public static final String AMOUNT_MIN = "amount must be at least " + MIN_AMOUNT_POISHA
            + " poisha (" + MIN_AMOUNT_POISHA / POISHA_PER_BDT + " BDT)";
    public static final String AMOUNT_MAX = "amount must be at most " + MAX_AMOUNT_POISHA
            + " poisha (" + MAX_AMOUNT_POISHA / POISHA_PER_BDT + " BDT)";

    public static final String PAYMENT_METHOD_REQUIRED = "payment_method is required";

    public static final String NOTE_SIZE = "note must not exceed " + NOTE_MAX_LENGTH + " characters";
}
