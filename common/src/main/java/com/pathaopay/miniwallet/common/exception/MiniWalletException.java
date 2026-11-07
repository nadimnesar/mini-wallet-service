package com.pathaopay.miniwallet.common.exception;

import lombok.Getter;

/**
 * Base of all business exceptions: carries the machine-readable {@code errorCode} that
 * {@code GlobalExceptionHandler} maps to an HTTP status.
 */
@Getter
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

    /**
     * Whether the failure is transient (e.g. a gateway outage). Retryable failures are not stored
     * against an Idempotency-Key, so the client can retry with the same key.
     */
    public boolean isRetryable() {
        return false;
    }
}
