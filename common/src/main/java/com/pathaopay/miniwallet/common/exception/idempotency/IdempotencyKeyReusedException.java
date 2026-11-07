package com.pathaopay.miniwallet.common.exception.idempotency;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** The Idempotency-Key was already used with a different request body. */
public class IdempotencyKeyReusedException extends MiniWalletException {
    public IdempotencyKeyReusedException(String message) {
        super(ErrorCode.IDEMPOTENCY_KEY_REUSED, message);
    }
}
