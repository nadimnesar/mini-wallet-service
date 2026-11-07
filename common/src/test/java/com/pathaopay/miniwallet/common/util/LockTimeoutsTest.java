package com.pathaopay.miniwallet.common.util;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockTimeoutsTest {

    @Test
    void recognizesALockTimeoutAnywhereInTheCauseChain() {
        SQLException lock = new SQLException("lock timeout", "55P03");

        assertTrue(LockTimeouts.isLockTimeout(lock));
        assertTrue(LockTimeouts.isLockTimeout(new RuntimeException("wrapped", new IllegalStateException(lock))));
    }

    @Test
    void otherErrorsAreNotLockTimeouts() {
        assertFalse(LockTimeouts.isLockTimeout(null));
        assertFalse(LockTimeouts.isLockTimeout(new RuntimeException("plain")));
        assertFalse(LockTimeouts.isLockTimeout(new SQLException("deadlock", "40P01")));
        assertFalse(LockTimeouts.isLockTimeout(new RuntimeException(new SQLException("no state"))));
    }
}
