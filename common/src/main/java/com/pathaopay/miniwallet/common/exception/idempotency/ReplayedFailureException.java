package com.pathaopay.miniwallet.common.exception.idempotency;

import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/**
 * Re-raises a failure stored against an Idempotency-Key, so a retried request gets exactly the
 * original error code and message (and therefore the same HTTP status).
 */
public class ReplayedFailureException extends MiniWalletException {
    public ReplayedFailureException(String errorCode, String message) {
        super(errorCode, message);
    }
}
