package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;

public class DuplicatePhoneException extends MiniWalletException {
    public DuplicatePhoneException(String message) {
        super(ErrorCodes.DUPLICATE_PHONE, message);
    }
}
