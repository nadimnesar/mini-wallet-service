package com.pathaopay.miniwallet.common.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.sql.SQLException;

/** Recognises the PostgreSQL error raised when a row lock is not granted within {@code lock_timeout}. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LockTimeouts {

    /** SQLSTATE lock_not_available. Spring leaves it uncategorized, so it is matched on the cause chain. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    public static boolean isLockTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
