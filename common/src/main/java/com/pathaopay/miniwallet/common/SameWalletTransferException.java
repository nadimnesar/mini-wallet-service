package com.pathaopay.miniwallet.common;

public class SameWalletTransferException extends MiniWalletException {
    public SameWalletTransferException(String message) {
        super("SAME_WALLET_TRANSFER", message);
    }
}
