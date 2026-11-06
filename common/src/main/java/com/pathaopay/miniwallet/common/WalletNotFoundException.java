package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorMessages;

public class WalletNotFoundException extends MiniWalletException {
    public WalletNotFoundException(String walletId) {
        super(ErrorCodes.WALLET_NOT_FOUND, ErrorMessages.WALLET_NOT_FOUND_PREFIX + walletId);
    }
}
