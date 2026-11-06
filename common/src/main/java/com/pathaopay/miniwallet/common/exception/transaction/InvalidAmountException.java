package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class InvalidAmountException extends MiniWalletException {
    public InvalidAmountException(String message) {
        super(ErrorCode.INVALID_AMOUNT, message);
    }
}
