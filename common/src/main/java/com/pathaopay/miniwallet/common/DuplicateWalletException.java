package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;

public class DuplicateWalletException extends MiniWalletException {
    public DuplicateWalletException(String message) {
        super(ErrorCodes.DUPLICATE_WALLET, message);
    }
}
