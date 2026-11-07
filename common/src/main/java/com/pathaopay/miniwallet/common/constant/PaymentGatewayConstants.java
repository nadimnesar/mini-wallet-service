package com.pathaopay.miniwallet.common.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Mock-able external payment gateway (one HTTP API per card network). */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PaymentGatewayConstants {
    /** Prefix of the {@code application.properties} keys for the gateway client. */
    public static final String PROPERTIES_PREFIX = "miniwallet.payment-gateway";
    /** Request header that carries the gateway API key. */
    public static final String API_KEY_HEADER = "X-Api-Key";
    /** Format with the lower-case network name, e.g. visa. */
    public static final String VERIFY_PATH = "/%s/v1/payments/verify";
}
