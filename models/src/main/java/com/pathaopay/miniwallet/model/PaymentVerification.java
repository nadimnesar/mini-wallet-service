package com.pathaopay.miniwallet.model;

import com.pathaopay.miniwallet.model.enums.PaymentMethod;

import java.util.UUID;

/**
 * What the gateway reports for a payment. {@code amount} is in poisha; {@code merchantReference} echoes the
 * reference (the customer wallet's phone) the payment was made for.
 */
public record PaymentVerification(
        UUID gatewayRef,
        Status status,
        long amount,
        String currency,
        PaymentMethod cardNetwork,
        String declineReason,
        String merchantReference
) {
    /** Gateway verdict: money taken, refused (see {@code declineReason}), or not settled yet (retryable). */
    public enum Status {
        CAPTURED,
        DECLINED,
        PENDING
    }
}
