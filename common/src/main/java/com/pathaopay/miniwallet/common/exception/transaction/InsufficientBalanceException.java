package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class InsufficientBalanceException extends MiniWalletException {
    public InsufficientBalanceException(String message) {
        super(ErrorCodes.INSUFFICIENT_BALANCE, message);
    }
}
