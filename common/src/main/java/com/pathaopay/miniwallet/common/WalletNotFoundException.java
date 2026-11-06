package com.pathaopay.miniwallet.common;

public class WalletNotFoundException extends MiniWalletException {
    public WalletNotFoundException(String walletId) {
        super("WALLET_NOT_FOUND", "Wallet not found with id: " + walletId);
    }
}
