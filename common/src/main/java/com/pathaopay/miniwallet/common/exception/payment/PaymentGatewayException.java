package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** The gateway could not be reached or answered unexpectedly. Transient: the request is retried later. */
public class PaymentGatewayException extends MiniWalletException {
    public PaymentGatewayException(String message, Throwable cause) {
        super(ErrorCode.PAYMENT_GATEWAY_ERROR, message, cause);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
