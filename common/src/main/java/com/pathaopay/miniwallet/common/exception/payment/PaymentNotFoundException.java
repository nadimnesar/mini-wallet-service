package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/**
 * The gateway has no payment for this reference. Retryable: a customer who has just paid may not be visible at the
 * gateway yet, and giving up at once would fail a paid request. A reference that never appears fails once the attempts
 * run out.
 */
public class PaymentNotFoundException extends MiniWalletException {
    public PaymentNotFoundException(String message) {
        super(ErrorCode.PAYMENT_NOT_FOUND, message);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
