package com.pathaopay.miniwallet.model;

import java.time.Instant;

/**
 * A wallet's balance in poisha: the {@code balance_after} of its latest ledger entry. {@code updatedAt} is when that
 * entry was written, i.e. when the balance last changed.
 */
public record WalletBalance(long balance, Instant updatedAt) {
}
