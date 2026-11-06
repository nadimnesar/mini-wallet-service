package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorMessages;

public class InvalidPhoneNumberException extends MiniWalletException {
    public InvalidPhoneNumberException() {
        super(ErrorCodes.INVALID_PHONE_NUMBER, ErrorMessages.INVALID_PHONE_NUMBER);
    }
}
