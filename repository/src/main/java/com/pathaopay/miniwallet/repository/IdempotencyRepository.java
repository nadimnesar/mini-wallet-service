package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.IdempotencyRecord;

import java.util.Optional;
import java.util.UUID;

/** Storage of Idempotency-Key claims and their outcome (a created resource or a stored deterministic error). */
public interface IdempotencyRepository {

    /**
     * Claims {@code key} for the current transaction. If another transaction holds an uncommitted
     * claim on the same key, this blocks until it commits (returns false) or rolls back (claims it).
     *
     * @return true if this transaction now owns the key, false if it was already used
     */
    boolean tryClaim(String key, String operation, String requestHash);

    /** The stored record for the key, or empty if it was never claimed. */
    Optional<IdempotencyRecord> findByKey(String key);

    /** Marks the claimed key as done and links the resource it created. */
    void completeWithResource(String key, UUID resourceId);

    /** Marks the claimed key as failed with a deterministic error, replayed on later calls with the same key. */
    void completeWithError(String key, String errorCode, String errorMessage);
}
