package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class DuplicateGatewayRefException extends MiniWalletException {
    public DuplicateGatewayRefException(String message) {
        super(ErrorCode.DUPLICATE_GATEWAY_REF, message);
    }
}
