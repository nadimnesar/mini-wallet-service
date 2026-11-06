package com.pathaopay.miniwallet.common.exception.wallet;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

import java.util.UUID;

public class WalletNotFoundException extends MiniWalletException {
    public WalletNotFoundException(UUID walletId) {
        super(ErrorCode.WALLET_NOT_FOUND, ErrorMessage.WALLET_NOT_FOUND_PREFIX + walletId);
    }
}
