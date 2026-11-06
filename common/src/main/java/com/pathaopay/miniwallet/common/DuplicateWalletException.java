package com.pathaopay.miniwallet.common;

public class DuplicateWalletException extends MiniWalletException {
    public DuplicateWalletException(String message) {
        super("DUPLICATE_WALLET", message);
    }
}
