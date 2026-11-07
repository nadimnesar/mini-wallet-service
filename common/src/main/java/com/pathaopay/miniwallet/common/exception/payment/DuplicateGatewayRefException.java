package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** The gateway_ref was already credited or is part of another open add-money request. */
public class DuplicateGatewayRefException extends MiniWalletException {
    public DuplicateGatewayRefException(String message) {
        super(ErrorCode.DUPLICATE_GATEWAY_REF, message);
    }
}
