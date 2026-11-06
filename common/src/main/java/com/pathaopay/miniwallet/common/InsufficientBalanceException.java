package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;

public class InsufficientBalanceException extends MiniWalletException {
    public InsufficientBalanceException(String message) {
        super(ErrorCodes.INSUFFICIENT_BALANCE, message);
    }
}
