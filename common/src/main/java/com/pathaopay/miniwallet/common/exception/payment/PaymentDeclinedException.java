package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** The gateway did not capture the payment (declined). Not retryable: the request fails at once. */
public class PaymentDeclinedException extends MiniWalletException {
    public PaymentDeclinedException(String message) {
        super(ErrorCode.PAYMENT_DECLINED, message);
    }
}
