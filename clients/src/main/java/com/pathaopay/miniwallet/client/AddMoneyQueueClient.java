package com.pathaopay.miniwallet.client;

import java.util.UUID;

/**
 * The message broker side of asynchronous add-money. A message carries only the request id; the request itself lives
 * in the database. Every method returns once the broker has confirmed the message, and throws if it could not.
 */
public interface AddMoneyQueueClient {

    /** Puts the request on the work queue. */
    void publish(UUID requestId);

    /**
     * Parks the request on the retry queue for its delay, after which the broker puts it back on the work queue.
     *
     * @param failedAttempts how many attempts have failed so far (1 after the first failure)
     */
    void scheduleRetry(UUID requestId, int failedAttempts);

    /** Moves a request whose retries ran out to the dead-letter queue, where an operator can inspect it. */
    void deadLetter(UUID requestId, String reason);
}
