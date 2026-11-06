package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;

public class PaymentDeclinedException extends MiniWalletException {
    public PaymentDeclinedException(String message) {
        super(ErrorCodes.PAYMENT_DECLINED, message);
    }
}
