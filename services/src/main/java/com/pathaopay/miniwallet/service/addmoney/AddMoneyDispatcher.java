package com.pathaopay.miniwallet.service.addmoney;

import com.pathaopay.miniwallet.client.AddMoneyQueueClient;
import com.pathaopay.miniwallet.client.config.properties.AddMoneyMessagingProperties;
import com.pathaopay.miniwallet.repository.AddMoneyRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Outbox relay: hands accepted add-money requests to the broker. A request is stored in the database in the same
 * transaction that accepts it, so a crash can never leave an accepted request without a message to come: this loop
 * publishes every PENDING request that was never published (and, as a safety net, republishes one whose message got
 * lost, which also covers a crash right after a batch was claimed). Publishing twice is harmless: processing skips a request that is no longer PENDING and the gateway_ref
 * credits only once.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AddMoneyDispatcher {

    private final AddMoneyRequestRepository addMoneyRequestRepository;
    private final AddMoneyQueueClient addMoneyQueueClient;
    private final AddMoneyMessagingProperties properties;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${miniwallet.messaging.add-money.dispatch-interval-ms:500}")
    public void dispatchScheduled() {
        try {
            int published = dispatch();
            if (published > 0) {
                log.debug("Dispatched {} add-money request(s)", published);
            }
        } catch (RuntimeException e) {
            log.warn("Add-money dispatch failed, will try again: {}", e.toString());
        }
    }

    /**
     * Publishes one batch without holding a database lock or connection while the broker confirms:
     * <ol>
     *   <li>a short transaction locks the batch and stamps {@code published_at} (claiming it, so other dispatchers and
     *   the next run skip it);</li>
     *   <li>the messages are published outside any transaction;</li>
     *   <li>if the broker fails, the requests not published are released again and the error is rethrown.</li>
     * </ol>
     * A crash between the claim and the publish leaves the requests claimed but unsent: the {@code republish-after}
     * safety net sends them later. A message published although its release then fails is only a duplicate, which is
     * harmless.
     *
     * @return how many requests were published
     */
    int dispatch() {
        List<UUID> claimed = transactionTemplate.execute(status -> {
            List<UUID> ids = addMoneyRequestRepository.lockDispatchable(
                    properties.dispatchBatchSize(), properties.republishAfter(),
                    properties.republishAfter().plus(Collections.max(properties.retryDelays())));
            addMoneyRequestRepository.markPublished(ids);
            return ids;
        });
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        int published = 0;
        try {
            for (UUID id : claimed) {
                addMoneyQueueClient.publish(id);
                published++;
            }
        } catch (RuntimeException e) {
            release(claimed.subList(published, claimed.size()));
            throw e;
        }
        return published;
    }

    private void release(List<UUID> unpublished) {
        try {
            addMoneyRequestRepository.markUnpublished(unpublished);
        } catch (RuntimeException e) {
            log.warn("Could not release {} unpublished add-money request(s), the republish safety net will send them: {}",
                    unpublished.size(), e.toString());
        }
    }
}
