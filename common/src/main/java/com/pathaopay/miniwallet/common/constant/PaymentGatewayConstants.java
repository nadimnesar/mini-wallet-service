package com.pathaopay.miniwallet.common.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Mock-able external payment gateway (one HTTP API per card network). */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PaymentGatewayConstants {
    public static final String PROPERTIES_PREFIX = "miniwallet.payment-gateway";
    public static final String API_KEY_HEADER = "X-Api-Key";
    /** Format with the lower-case network name, e.g. visa. */
    public static final String VERIFY_PATH = "/%s/v1/payments/verify";
}
