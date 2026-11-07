package com.pathaopay.miniwallet.common.exception.transaction;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** The history filter has {@code from} after {@code to}. */
public class InvalidDateRangeException extends MiniWalletException {
    public InvalidDateRangeException() {
        super(ErrorCode.INVALID_DATE_RANGE, ErrorMessage.INVALID_DATE_RANGE);
    }
}
