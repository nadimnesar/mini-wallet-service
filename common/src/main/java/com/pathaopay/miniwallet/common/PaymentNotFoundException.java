package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;

public class PaymentNotFoundException extends MiniWalletException {
    public PaymentNotFoundException(String message) {
        super(ErrorCodes.PAYMENT_NOT_FOUND, message);
    }
}
