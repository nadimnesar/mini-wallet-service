package com.pathaopay.miniwallet.common.util;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Generates UUIDv7 identifiers (RFC 9562): a 48-bit Unix-millisecond timestamp followed by random
 * bits. They are unique, sort by creation time and keep B-tree primary-key inserts index-friendly.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UuidGenerator {

    /** Thread-safe; monotonic within the same millisecond. */
    private static final TimeBasedEpochGenerator V7 = Generators.timeBasedEpochGenerator();

    public static UUID v7() {
        return V7.generate();
    }
}
