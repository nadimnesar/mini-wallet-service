package com.pathaopay.miniwallet.model;

import java.time.Instant;
import java.util.UUID;

/** A persisted wallet. {@code id} is a UUIDv7; {@code balance} is in poisha. */
public record Wallet(
        UUID id,
        String userId,
        String phone,
        Currency currency,
        long balance,
        Instant createdAt,
        Instant updatedAt
) {}
