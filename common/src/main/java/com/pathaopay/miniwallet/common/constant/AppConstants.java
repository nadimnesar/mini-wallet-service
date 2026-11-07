package com.pathaopay.miniwallet.common.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Application-wide constants: HTTP headers, money rules, phone formats and request validation. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AppConstants {

    // ---- HTTP headers
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    // ---- Money: all amounts are integer poisha (1 BDT = 100 poisha)
    public static final long POISHA_PER_BDT = 100;
    /** Phone numbers of the seeded system accounts. Phones are unique, so no other wallet can take one. */
    public static final String REVENUE_WALLET_PHONE = "01000000001";
    public static final String GATEWAY_CLEARING_WALLET_PHONE = "01000000003";
    /** REVENUE account that collects transfer fees; seeded by Liquibase changeset 001-seed-system-wallets. */
    public static final UUID REVENUE_WALLET_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    /**
     * ASSET account for money owed to the platform by the card gateways: debited by every add-money, so that each
     * customer credit has a matching debit. Seeded by Liquibase changeset 001-seed-system-wallets.
     */
    public static final UUID GATEWAY_CLEARING_WALLET_ID = UUID.fromString("00000000-0000-7000-8000-000000000003");
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
    public static final int NOTE_MAX_LENGTH = 255;

    public static final String USER_ID_REQUIRED = "user_id is required";

    public static final String TYPE_REQUIRED = "type is required";

    public static final String PHONE_REQUIRED = "phone is required";
    public static final String PHONE_FORMAT = "phone must be a Bangladeshi mobile number in one of the formats "
            + "1XXXXXXXXX, 01XXXXXXXXX, 8801XXXXXXXXX or +8801XXXXXXXXX";

    public static final String FROM_PHONE_REQUIRED = "from_phone is required";

    public static final String TO_PHONE_REQUIRED = "to_phone is required";

    public static final String GATEWAY_REF_REQUIRED = "gateway_ref is required";

    public static final String AMOUNT_REQUIRED = "amount is required";

    public static final String PAYMENT_METHOD_REQUIRED = "payment_method is required";

    public static final String NOTE_SIZE = "note must be 1 to " + NOTE_MAX_LENGTH + " characters";
}
