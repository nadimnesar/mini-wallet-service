package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class PaymentNotFoundException extends MiniWalletException {
    public PaymentNotFoundException(String message) {
        super(ErrorCodes.PAYMENT_NOT_FOUND, message);
    }
}
