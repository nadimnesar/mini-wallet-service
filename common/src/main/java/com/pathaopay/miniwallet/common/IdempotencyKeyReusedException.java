package com.pathaopay.miniwallet.common;

public class IdempotencyKeyReusedException extends MiniWalletException {
    public IdempotencyKeyReusedException(String message) {
        super("IDEMPOTENCY_KEY_REUSED", message);
    }
}
