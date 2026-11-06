package com.pathaopay.miniwallet.common.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidGeneratorTest {

    @Test
    void generatesVersion7RfcVariantUuids() {
        UUID id = UuidGenerator.v7();

        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void embedsCurrentUnixMillisecondTimestamp() {
        long before = System.currentTimeMillis();
        UUID id = UuidGenerator.v7();
        long after = System.currentTimeMillis();

        long timestamp = id.getMostSignificantBits() >>> 16;
        assertTrue(timestamp >= before && timestamp <= after);
    }

    @Test
    void successiveIdsAreUniqueAndTimeOrdered() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidGenerator.v7().toString());
        }

        assertEquals(ids.size(), new HashSet<>(ids).size());
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        assertEquals(sorted, ids);
    }
}
