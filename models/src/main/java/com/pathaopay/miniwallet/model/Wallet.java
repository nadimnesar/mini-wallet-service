package com.pathaopay.miniwallet.model;

import java.time.Instant;

/** A persisted wallet. {@code balance} is in poisha. */
public record Wallet(
        String id,
        String userId,
        String phone,
        Currency currency,
        long balance,
        Instant createdAt,
        Instant updatedAt
) {}
