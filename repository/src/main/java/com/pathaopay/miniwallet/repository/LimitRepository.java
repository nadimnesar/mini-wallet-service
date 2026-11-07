package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.TransactionLimit;
import com.pathaopay.miniwallet.model.enums.TransactionType;

import java.util.Optional;

/** Read access to the per-transaction min/max limits in the {@code limits} table. */
public interface LimitRepository {

    /** The limit currently configured for a transaction type, if any. */
    Optional<TransactionLimit> find(TransactionType type);
}
