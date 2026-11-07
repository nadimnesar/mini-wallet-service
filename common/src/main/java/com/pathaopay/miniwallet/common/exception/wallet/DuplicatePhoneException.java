package com.pathaopay.miniwallet.common.exception.wallet;

import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorCode;
import com.pathaopay.miniwallet.common.exception.MiniWalletException;

/** A wallet already exists for the phone number (phones are unique). */
public class DuplicatePhoneException extends MiniWalletException {
    public DuplicatePhoneException(String message) {
        super(ErrorCode.DUPLICATE_PHONE, message);
    }
}
