package com.pathaopay.miniwallet.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A wallet's static identity. {@code id} is a UUIDv7. The balance is not stored on the wallet: see
 * {@link WalletBalance}, derived from the wallet's latest ledger entry.
 */
public record Wallet(
        UUID id,
        UUID userId,
        String phone,
        Currency currency,
        WalletType type,
        Instant createdAt
) {}
