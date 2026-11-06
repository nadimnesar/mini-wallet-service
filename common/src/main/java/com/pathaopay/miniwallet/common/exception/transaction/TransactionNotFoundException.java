package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorCodes;
import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorMessages;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

public class TransactionNotFoundException extends MiniWalletException {
    public TransactionNotFoundException(String transactionId) {
        super(ErrorCodes.TRANSACTION_NOT_FOUND, ErrorMessages.TRANSACTION_NOT_FOUND_PREFIX + transactionId);
    }
}
