package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** The gateway confirmation does not match the request (amount, wallet, ...). Not retryable. */
public class PaymentMismatchException extends MiniWalletException {
    public PaymentMismatchException(String message) {
        super(ErrorCode.PAYMENT_MISMATCH, message);
    }
}
