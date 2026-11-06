package com.pathaopay.miniwallet.common.exception.wallet;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class DuplicatePhoneException extends MiniWalletException {
    public DuplicatePhoneException(String message) {
        super(ErrorCodes.DUPLICATE_PHONE, message);
    }
}
