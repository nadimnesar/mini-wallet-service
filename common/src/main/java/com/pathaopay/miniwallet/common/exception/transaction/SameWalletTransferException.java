package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** Sender and receiver of a transfer are the same wallet. */
public class SameWalletTransferException extends MiniWalletException {
    public SameWalletTransferException(String message) {
        super(ErrorCode.SAME_WALLET_TRANSFER, message);
    }
}
