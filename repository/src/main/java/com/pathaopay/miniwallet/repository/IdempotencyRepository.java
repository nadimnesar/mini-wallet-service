package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.IdempotencyRecord;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {

    /**
     * Claims {@code key} for the current transaction. If another transaction holds an uncommitted
     * claim on the same key, this blocks until it commits (returns false) or rolls back (claims it).
     *
     * @return true if this transaction now owns the key, false if it was already used
     */
    boolean tryClaim(String key, String operation, String requestHash);

    Optional<IdempotencyRecord> findByKey(String key);

    void completeWithResource(String key, UUID resourceId);

    void completeWithError(String key, String errorCode, String errorMessage);
}
