package com.pathaopay.miniwallet.common;

public class InvalidAmountException extends MiniWalletException {
    public InvalidAmountException(String message) {
        super("INVALID_AMOUNT", message);
    }
}
