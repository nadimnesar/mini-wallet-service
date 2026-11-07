package com.pathaopay.miniwallet.model;

import java.time.Instant;
import java.util.UUID;

/** Optional criteria for listing ledger entries; a null field means "no restriction". */
public record TransactionFilter(UUID walletId, EntryType type, Instant from, Instant to) {
}
