package com.pathaopay.miniwallet.common;

public class InsufficientBalanceException extends MiniWalletException {
    public InsufficientBalanceException(String message) {
        super("INSUFFICIENT_BALANCE", message);
    }
}
