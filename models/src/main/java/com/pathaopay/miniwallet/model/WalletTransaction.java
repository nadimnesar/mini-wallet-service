package com.pathaopay.miniwallet.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable ledger entry. {@code entryNo} is its position in the wallet's chain (1, 2, 3, ...).
 * {@code referenceId} is the gateway_ref for ADD_MONEY and the transfer id for transfers.
 * {@code paymentMethod} is set only for ADD_MONEY. {@code createdAt} is null until persisted.
 */
public record WalletTransaction(
        UUID id,
        UUID walletId,
        long entryNo,
        TransactionType type,
        long amount,
        long balanceBefore,
        long balanceAfter,
        UUID counterpartyWalletId,
        UUID referenceId,
        PaymentMethod paymentMethod,
        Instant createdAt
) {}
