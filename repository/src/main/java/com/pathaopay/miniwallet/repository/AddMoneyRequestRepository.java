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
     * {@code republishAfter} ago (a lost message), skipping rows other dispatchers hold. A request that was already
     * attempted is waiting on a retry queue, so it only counts as lost when its last attempt is older than
     * {@code parkedAfter} too.
     */
    List<UUID> lockDispatchable(int limit, Duration republishAfter, Duration parkedAfter);

    /**
     * Stamps {@code published_at} on the requests. The dispatcher does this to claim a batch <em>before</em> it
     * publishes, so no row lock is held while it waits for the broker.
     */
    void markPublished(List<UUID> ids);

    /** Clears {@code published_at} of still-PENDING requests the dispatcher could not publish, so the next run retries them. */
    void markUnpublished(List<UUID> ids);
}
