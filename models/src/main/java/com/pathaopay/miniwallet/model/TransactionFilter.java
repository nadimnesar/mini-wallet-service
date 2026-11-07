package com.pathaopay.miniwallet.model;

import com.pathaopay.miniwallet.model.enums.EntryType;
import java.time.Instant;
import java.util.UUID;

/** Criteria for listing one wallet's ledger entries; a null type, from or to means "no restriction". */
public record TransactionFilter(UUID walletId, EntryType type, Instant from, Instant to) {
}
