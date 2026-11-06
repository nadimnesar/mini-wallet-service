package com.pathaopay.miniwallet.common.exception.wallet;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class DuplicateWalletException extends MiniWalletException {
    public DuplicateWalletException(String message) {
        super(ErrorCodes.DUPLICATE_WALLET, message);
    }
}
