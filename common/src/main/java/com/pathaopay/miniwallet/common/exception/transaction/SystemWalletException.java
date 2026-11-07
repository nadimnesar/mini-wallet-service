package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** A system ledger account (asset, revenue, ...) was used where only a customer wallet is allowed. */
public class SystemWalletException extends MiniWalletException {
    public SystemWalletException(String message) {
        super(ErrorCode.SYSTEM_WALLET_NOT_ALLOWED, message);
    }
}
