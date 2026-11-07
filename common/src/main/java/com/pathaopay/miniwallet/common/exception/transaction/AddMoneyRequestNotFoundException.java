package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** No add-money request exists with the given id. */
public class AddMoneyRequestNotFoundException extends MiniWalletException {
    public AddMoneyRequestNotFoundException(String requestId) {
        super(ErrorCode.ADD_MONEY_REQUEST_NOT_FOUND, ErrorMessage.ADD_MONEY_REQUEST_NOT_FOUND_PREFIX + requestId);
    }
}
