package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.TransactionLimit;
import com.pathaopay.miniwallet.model.TransactionType;

import java.util.Optional;

public interface LimitRepository {

    /** The limit currently configured for a transaction type, if any. */
    Optional<TransactionLimit> find(TransactionType type);
}
