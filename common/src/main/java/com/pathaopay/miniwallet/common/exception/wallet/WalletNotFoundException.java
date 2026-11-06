package com.pathaopay.miniwallet.common.exception.wallet;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorMessages;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

import java.util.UUID;

public class WalletNotFoundException extends MiniWalletException {
    public WalletNotFoundException(UUID walletId) {
        super(ErrorCodes.WALLET_NOT_FOUND, ErrorMessages.WALLET_NOT_FOUND_PREFIX + walletId);
    }
}
