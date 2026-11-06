package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** The gateway could not be reached or answered unexpectedly. Transient: the client may retry. */
public class PaymentGatewayException extends MiniWalletException {
    public PaymentGatewayException(String message, Throwable cause) {
        super(ErrorCodes.PAYMENT_GATEWAY_ERROR, message, cause);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
