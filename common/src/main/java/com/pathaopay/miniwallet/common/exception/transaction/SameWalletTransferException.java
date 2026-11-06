package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class SameWalletTransferException extends MiniWalletException {
    public SameWalletTransferException(String message) {
        super(ErrorCodes.SAME_WALLET_TRANSFER, message);
    }
}
