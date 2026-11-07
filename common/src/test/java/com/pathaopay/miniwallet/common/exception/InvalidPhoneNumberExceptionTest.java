package com.pathaopay.miniwallet.common.exception;

import com.pathaopay.miniwallet.common.exception.wallet.InvalidPhoneNumberException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InvalidPhoneNumberExceptionTest {

    @Test
    void customMessageKeepsTheErrorCodeAndDefaultMessageDiffers() {
        InvalidPhoneNumberException custom = new InvalidPhoneNumberException("bad: 123");
        InvalidPhoneNumberException standard = new InvalidPhoneNumberException();

        assertEquals("bad: 123", custom.getMessage());
        assertEquals(standard.getErrorCode(), custom.getErrorCode());
        assertFalse(custom.isRetryable());
    }
}
