package com.pathaopay.miniwallet.common.util;

import com.pathaopay.miniwallet.common.exception.wallet.InvalidPhoneNumberException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneNumberUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {"1711000001", "  1711000001 ", "01711000001", "8801711000001", "+8801711000001", "  +8801711000001  "})
    void normalizesAllAcceptedFormatsToLocalForm(String input) {
        assertTrue(PhoneNumberUtils.isValid(input));
        assertEquals("01711000001", PhoneNumberUtils.normalize(input));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "171100000", "17110000012", "0171100000", "017110000012", "+01711000001",
            "+88 01711000001", "008801711000001", "880171100000", "881711000001", "8801711000001 2", "2711000001", "+8801711000001x", "0271100000a"})
    void rejectsInvalidNumbers(String input) {
        assertFalse(PhoneNumberUtils.isValid(input));
        assertThrows(InvalidPhoneNumberException.class, () -> PhoneNumberUtils.normalize(input));
    }

    @Test
    void normalizedOutputIsStable() {
        String once = PhoneNumberUtils.normalize("+8801711000001");
        assertEquals(once, PhoneNumberUtils.normalize(once));
    }
}
