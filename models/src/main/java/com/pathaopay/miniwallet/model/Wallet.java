package com.pathaopay.miniwallet.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A wallet. {@code id} is a UUIDv7. {@code balance} (poisha) is not stored on the wallet: it is the
 * {@code balance_after} of the wallet's latest ledger entry, or 0 when it has none.
 */
public record Wallet(
        UUID id,
        String userId,
        String phone,
        Currency currency,
        long balance,
        Instant createdAt,
        Instant updatedAt
) {}
