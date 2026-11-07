package com.pathaopay.miniwallet.model;

import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.model.enums.EntryType;
import com.pathaopay.miniwallet.model.enums.EntryDirection;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable ledger leg. {@code entryNo} is its position in the wallet's chain (1, 2, 3, ...).
 * {@code transactionId} is the transaction the leg belongs to (its legs' debits equal their credits).
 * {@code walletType} is the wallet's account class, copied so the database can check the arithmetic.
 * {@code createdAt} is null until persisted.
 */
public record LedgerEntry(
        UUID id,
        UUID walletId,
        WalletType walletType,
        long entryNo,
        EntryType type,
        EntryDirection direction,
        long amount,
        long balanceBefore,
        long balanceAfter,
        UUID counterpartyWalletId,
        UUID transactionId,
        Instant createdAt
) {}
