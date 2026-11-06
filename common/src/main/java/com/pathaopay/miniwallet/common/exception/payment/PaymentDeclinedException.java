package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class PaymentDeclinedException extends MiniWalletException {
    public PaymentDeclinedException(String message) {
        super(ErrorCodes.PAYMENT_DECLINED, message);
    }
}
