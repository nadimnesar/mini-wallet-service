package com.pathaopay.miniwallet.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One business operation: a group of ledger entries whose debits equal its credits. {@code amount} is the principal
 * moved (a transfer's fee is a separate entry pair). {@code gatewayRef} and {@code paymentMethod} are set only for
 * ADD_MONEY; {@code note} only for TRANSFER (optional). {@code createdAt} is null until persisted.
 */
public record LedgerTransaction(
        UUID id,
        TransactionType type,
        long amount,
        UUID gatewayRef,
        PaymentMethod paymentMethod,
        String note,
        Instant createdAt
) {}
