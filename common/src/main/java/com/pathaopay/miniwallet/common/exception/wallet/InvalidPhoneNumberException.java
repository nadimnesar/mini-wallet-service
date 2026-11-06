package com.pathaopay.miniwallet.common.exception.wallet;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorMessages;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class InvalidPhoneNumberException extends MiniWalletException {
    public InvalidPhoneNumberException() {
        super(ErrorCodes.INVALID_PHONE_NUMBER, ErrorMessages.INVALID_PHONE_NUMBER);
    }
}
