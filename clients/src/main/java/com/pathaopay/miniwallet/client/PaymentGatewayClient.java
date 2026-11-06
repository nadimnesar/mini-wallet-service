package com.pathaopay.miniwallet.client;

import com.pathaopay.miniwallet.common.exception.payment.PaymentGatewayException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentNotFoundException;
import com.pathaopay.miniwallet.model.PaymentMethod;

import java.util.UUID;

/** Asks the card network's payment gateway what it knows about a payment. */
public interface PaymentGatewayClient {

    /**
     * Looks up the payment {@code gatewayRef} on the {@code method}'s gateway. Deciding whether the
     * result is acceptable (captured, right amount/currency/network) is the caller's job.
     *
     * @throws PaymentNotFoundException if the gateway has no such payment
     * @throws PaymentGatewayException  if the gateway is unreachable, times out or answers unexpectedly
     */
    PaymentVerification verify(PaymentMethod method, UUID gatewayRef, long amount);
}
