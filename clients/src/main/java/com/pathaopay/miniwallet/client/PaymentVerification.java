package com.pathaopay.miniwallet.client;

import com.pathaopay.miniwallet.model.PaymentMethod;

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
        String authorizationCode,
        String declineReason,
        String merchantReference
) {
    public enum Status {
        CAPTURED,
        DECLINED,
        PENDING
    }
}
