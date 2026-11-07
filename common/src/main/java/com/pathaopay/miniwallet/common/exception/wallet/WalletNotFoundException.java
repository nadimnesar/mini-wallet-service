package com.pathaopay.miniwallet.common.exception.wallet;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** No wallet exists for the given phone number. */
public class WalletNotFoundException extends MiniWalletException {
    public WalletNotFoundException(String phone) {
        super(ErrorCode.WALLET_NOT_FOUND, ErrorMessage.WALLET_NOT_FOUND_PREFIX + phone);
    }
}
