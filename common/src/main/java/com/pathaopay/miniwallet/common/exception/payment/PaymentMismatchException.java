package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class PaymentMismatchException extends MiniWalletException {
    public PaymentMismatchException(String message) {
        super(ErrorCodes.PAYMENT_MISMATCH, message);
    }
}
