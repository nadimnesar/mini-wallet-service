package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;

public class SameWalletTransferException extends MiniWalletException {
    public SameWalletTransferException(String message) {
        super(ErrorCodes.SAME_WALLET_TRANSFER, message);
    }
}
