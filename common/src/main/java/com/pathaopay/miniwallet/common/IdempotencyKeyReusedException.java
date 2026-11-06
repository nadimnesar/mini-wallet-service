package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;

public class IdempotencyKeyReusedException extends MiniWalletException {
    public IdempotencyKeyReusedException(String message) {
        super(ErrorCodes.IDEMPOTENCY_KEY_REUSED, message);
    }
}
