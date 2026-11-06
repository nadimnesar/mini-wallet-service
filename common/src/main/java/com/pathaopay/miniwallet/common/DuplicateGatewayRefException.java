package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;

public class DuplicateGatewayRefException extends MiniWalletException {
    public DuplicateGatewayRefException(String message) {
        super(ErrorCodes.DUPLICATE_GATEWAY_REF, message);
    }
}
