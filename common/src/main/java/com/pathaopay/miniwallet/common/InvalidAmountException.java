package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;

public class InvalidAmountException extends MiniWalletException {
    public InvalidAmountException(String message) {
        super(ErrorCodes.INVALID_AMOUNT, message);
    }
}
