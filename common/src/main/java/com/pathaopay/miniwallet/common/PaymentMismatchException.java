package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;

public class PaymentMismatchException extends MiniWalletException {
    public PaymentMismatchException(String message) {
        super(ErrorCodes.PAYMENT_MISMATCH, message);
    }
}
