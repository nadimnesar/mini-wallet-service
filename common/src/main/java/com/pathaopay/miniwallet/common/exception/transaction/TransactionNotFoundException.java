package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** No transaction exists with the given id. */
public class TransactionNotFoundException extends MiniWalletException {
    public TransactionNotFoundException(String transactionId) {
        super(ErrorCode.TRANSACTION_NOT_FOUND, ErrorMessage.TRANSACTION_NOT_FOUND_PREFIX + transactionId);
    }
}
