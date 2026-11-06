package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class InvalidAmountException extends MiniWalletException {
    public InvalidAmountException(String message) {
        super(ErrorCodes.INVALID_AMOUNT, message);
    }
}
