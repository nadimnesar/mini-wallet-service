package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.AddMoneyRequestRecord;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to {@code add_money_requests}. The table is both the status record the API reads and the outbox the
 * dispatcher publishes from: a PENDING row with no {@code published_at} still has to be sent to the broker.
 */
public interface AddMoneyRequestRepository {

    /**
     * Inserts a PENDING request.
     *
     * @return the stored request, or empty if its gateway_ref already belongs to a PENDING or COMPLETED request
     */
    Optional<AddMoneyRequestRecord> insert(AddMoneyRequestRecord request);

    Optional<AddMoneyRequestRecord> findById(UUID id);

    /** Like {@link #findById} but locks the row until the transaction ends, so a request is processed by one worker at a time. */
    Optional<AddMoneyRequestRecord> lockById(UUID id);

    void markCompleted(UUID id, UUID transactionId, int attempts);

    void markFailed(UUID id, int attempts, String errorCode, String errorMessage);

    /** Records a retryable failure and the attempt count; the request stays PENDING. */
    void recordRetry(UUID id, int attempts, String errorCode, String errorMessage);

    /**
     * Locks up to {@code limit} PENDING requests that were never published, or last published more than
     * {@code republishAfter} ago (a lost message), skipping rows other dispatchers hold.
     */
    List<UUID> lockDispatchable(int limit, Duration republishAfter);

    void markPublished(List<UUID> ids);
}
