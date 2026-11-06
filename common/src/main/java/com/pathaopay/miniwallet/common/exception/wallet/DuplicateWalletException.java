package com.pathaopay.miniwallet.common.exception.wallet;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class DuplicateWalletException extends MiniWalletException {
    public DuplicateWalletException(String message) {
        super(ErrorCode.DUPLICATE_WALLET, message);
    }
}
