package com.pathaopay.miniwallet.common.exception.payment;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class DuplicateGatewayRefException extends MiniWalletException {
    public DuplicateGatewayRefException(String message) {
        super(ErrorCodes.DUPLICATE_GATEWAY_REF, message);
    }
}
