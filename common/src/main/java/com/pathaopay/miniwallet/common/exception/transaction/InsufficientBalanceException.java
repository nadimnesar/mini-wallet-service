package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class InsufficientBalanceException extends MiniWalletException {
    public InsufficientBalanceException(String message) {
        super(ErrorCode.INSUFFICIENT_BALANCE, message);
    }
}
