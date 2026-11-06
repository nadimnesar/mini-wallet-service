package com.pathaopay.miniwallet.common;

public class DuplicateGatewayRefException extends MiniWalletException {
    public DuplicateGatewayRefException(String message) {
        super("DUPLICATE_GATEWAY_REF", message);
    }
}
