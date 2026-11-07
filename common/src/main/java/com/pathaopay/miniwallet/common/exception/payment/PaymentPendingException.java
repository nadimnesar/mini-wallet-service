package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/**
 * The gateway has not captured the payment yet.
 * Transient: it is checked again later.
 */
public class PaymentPendingException extends MiniWalletException {
    public PaymentPendingException(String message) {
        super(ErrorCode.PAYMENT_PENDING, message);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
