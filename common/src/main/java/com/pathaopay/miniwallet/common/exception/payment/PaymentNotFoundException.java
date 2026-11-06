package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class PaymentNotFoundException extends MiniWalletException {
    public PaymentNotFoundException(String message) {
        super(ErrorCode.PAYMENT_NOT_FOUND, message);
    }
}
