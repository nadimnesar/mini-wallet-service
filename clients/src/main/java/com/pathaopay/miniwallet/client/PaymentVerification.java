package com.pathaopay.miniwallet.client;

import com.pathaopay.miniwallet.model.PaymentMethod;

/** What the gateway reports for a payment. {@code amount} is in poisha. */
public record PaymentVerification(
        String gatewayRef,
        Status status,
        long amount,
        String currency,
        PaymentMethod cardNetwork,
        String authorizationCode,
        String declineReason
) {
    public enum Status {
        CAPTURED,
        DECLINED,
        PENDING
    }
}
