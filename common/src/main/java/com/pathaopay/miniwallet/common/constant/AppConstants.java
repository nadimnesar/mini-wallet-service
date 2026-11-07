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
    public static final long MIN_AMOUNT_POISHA = 100;
    public static final long MAX_AMOUNT_POISHA = 2_500_000;
    /** Owner (user_id) shared by every system account; no customer wallet can use it. Seeded by Liquibase. */
    public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    /** EXPENSE account for the platform's operating costs; no flow posts to it yet. */
    public static final UUID OPERATING_EXPENSE_WALLET_ID = UUID.fromString("00000000-0000-7000-8000-000000000005");
    /** EQUITY account for the owners' capital; no flow posts to it yet. */
    public static final UUID OWNER_EQUITY_WALLET_ID = UUID.fromString("00000000-0000-7000-8000-000000000006");
    /**
     * Phone numbers of the system accounts. The 010 prefix is not assigned to any operator, and customer wallets
     * can only be registered with an assigned prefix ({@link #CUSTOMER_PHONE_ACCEPTED_PATTERN}), so no customer can
     * ever hold a system number.
     */
    public static final String REVENUE_WALLET_PHONE = "01000000001";
    public static final String GATEWAY_CLEARING_WALLET_PHONE = "01000000003";
    public static final String OPERATING_EXPENSE_WALLET_PHONE = "01000000005";
    public static final String OWNER_EQUITY_WALLET_PHONE = "01000000006";
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
    /** Same formats, but only the operator prefixes in use (013-019); required to register a customer wallet. */
    public static final String CUSTOMER_PHONE_ACCEPTED_PATTERN = "^(?:\\+?880|0)?(1[3-9]\\d{8})$";

    // ---- Request validation limits, patterns and messages
    public static final int NOTE_MAX_LENGTH = 255;

    public static final String USER_ID_REQUIRED = "user_id is required";

    public static final String PHONE_REQUIRED = "phone is required";
    public static final String PHONE_FORMAT = "phone must be a Bangladeshi mobile number in one of the formats "
            + "1XXXXXXXXX, 01XXXXXXXXX, 8801XXXXXXXXX or +8801XXXXXXXXX";
    public static final String CUSTOMER_PHONE_FORMAT = PHONE_FORMAT + ", with an assigned operator prefix (013-019)";

    public static final String FROM_PHONE_REQUIRED = "from_phone is required";

    public static final String TO_PHONE_REQUIRED = "to_phone is required";

    public static final String GATEWAY_REF_REQUIRED = "gateway_ref is required";

    public static final String AMOUNT_REQUIRED = "amount is required";
    public static final String AMOUNT_MIN = "amount must be at least " + MIN_AMOUNT_POISHA
            + " poisha (" + MIN_AMOUNT_POISHA / POISHA_PER_BDT + " BDT)";
    public static final String AMOUNT_MAX = "amount must be at most " + MAX_AMOUNT_POISHA
            + " poisha (" + MAX_AMOUNT_POISHA / POISHA_PER_BDT + " BDT)";

    public static final String PAYMENT_METHOD_REQUIRED = "payment_method is required";

    public static final String NOTE_SIZE = "note must be 1 to " + NOTE_MAX_LENGTH + " characters";
}
