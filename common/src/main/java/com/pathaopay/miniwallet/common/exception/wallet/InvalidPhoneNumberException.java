package com.pathaopay.miniwallet.common.exception.wallet;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class InvalidPhoneNumberException extends MiniWalletException {
    public InvalidPhoneNumberException() {
        super(ErrorCode.INVALID_PHONE_NUMBER, ErrorMessage.INVALID_PHONE_NUMBER);
    }
}
