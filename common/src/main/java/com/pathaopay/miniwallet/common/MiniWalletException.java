package com.pathaopay.miniwallet.common;

public abstract class MiniWalletException extends RuntimeException {

    private final String errorCode;

    protected MiniWalletException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected MiniWalletException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
