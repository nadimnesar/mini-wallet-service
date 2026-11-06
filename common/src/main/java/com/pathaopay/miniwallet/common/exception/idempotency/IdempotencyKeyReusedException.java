package com.pathaopay.miniwallet.common.exception.idempotency;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class IdempotencyKeyReusedException extends MiniWalletException {
    public IdempotencyKeyReusedException(String message) {
        super(ErrorCodes.IDEMPOTENCY_KEY_REUSED, message);
    }
}
