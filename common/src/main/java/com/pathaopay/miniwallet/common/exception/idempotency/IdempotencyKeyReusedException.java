package com.pathaopay.miniwallet.common.exception.idempotency;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class IdempotencyKeyReusedException extends MiniWalletException {
    public IdempotencyKeyReusedException(String message) {
        super(ErrorCode.IDEMPOTENCY_KEY_REUSED, message);
    }
}
