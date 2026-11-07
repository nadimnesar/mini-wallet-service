package com.pathaopay.miniwallet.service.addmoney;

import com.pathaopay.miniwallet.client.AddMoneyQueueClient;
import com.pathaopay.miniwallet.client.messaging.AddMoneyMessagingProperties;
import com.pathaopay.miniwallet.repository.AddMoneyRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Outbox relay: hands accepted add-money requests to the broker. A request is stored in the database in the same
 * transaction that accepts it, so a crash can never leave an accepted request without a message to come: this loop
 * publishes every PENDING request that was never published (and, as a safety net, republishes one whose message got
 * lost). Publishing twice is harmless: processing skips a request that is no longer PENDING and the gateway_ref
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
     * Publishes one batch. The rows stay locked (other dispatchers skip them) until every message was confirmed and
     * marked; if the broker fails midway, nothing is marked and the whole batch is published again next time.
     *
     * @return how many requests were published
     */
    int dispatch() {
        Integer published = transactionTemplate.execute(status -> {
            List<UUID> ids = addMoneyRequestRepository.lockDispatchable(
                    properties.dispatchBatchSize(), properties.republishAfter());
            for (UUID id : ids) {
                addMoneyQueueClient.publish(id);
            }
            addMoneyRequestRepository.markPublished(ids);
            return ids.size();
        });
        return published == null ? 0 : published;
    }
}
