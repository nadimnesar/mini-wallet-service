package com.pathaopay.miniwallet.common;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorMessages;

public class TransactionNotFoundException extends MiniWalletException {
    public TransactionNotFoundException(String transactionId) {
        super(ErrorCodes.TRANSACTION_NOT_FOUND, ErrorMessages.TRANSACTION_NOT_FOUND_PREFIX + transactionId);
    }
}
