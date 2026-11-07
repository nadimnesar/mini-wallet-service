package com.pathaopay.miniwallet.common.exception.wallet;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** The request used the user_id reserved for system accounts: invalid input, not a duplicate wallet. */
public class ReservedUserIdException extends MiniWalletException {
    public ReservedUserIdException() {
        super(ErrorCode.VALIDATION_ERROR, ErrorMessage.RESERVED_USER_ID);
    }
}
