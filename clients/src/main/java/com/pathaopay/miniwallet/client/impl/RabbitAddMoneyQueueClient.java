package com.pathaopay.miniwallet.client.impl;

import com.pathaopay.miniwallet.client.AddMoneyQueueClient;
import com.pathaopay.miniwallet.client.config.properties.AddMoneyMessagingProperties;
import com.pathaopay.miniwallet.common.constant.MessagingConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * RabbitMQ implementation. Messages are persistent (the String converter default) and published on one channel
 * that waits for the broker's confirm (publisher confirms are switched on in application.properties), so a message is
 * never reported as sent while it could still be lost.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RabbitAddMoneyQueueClient implements AddMoneyQueueClient {

    private final RabbitTemplate rabbitTemplate;
    private final AddMoneyMessagingProperties properties;

    @Override
    public void publish(UUID requestId) {
        send(MessagingConstants.ADD_MONEY_QUEUE, requestId, message -> message);
    }

    @Override
    public void scheduleRetry(UUID requestId, int failedAttempts) {
        if (failedAttempts < 1 || failedAttempts > properties.retryDelays().size()) {
            throw new IllegalArgumentException("No retry queue for failed attempt " + failedAttempts);
        }
        String queue = MessagingConstants.ADD_MONEY_RETRY_QUEUE.formatted(failedAttempts);
        send(queue, requestId, message -> message);
        log.info("Add-money request scheduled for retry: requestId={} failedAttempts={} delay={}",
                requestId, failedAttempts, properties.retryDelays().get(failedAttempts - 1));
    }

    @Override
    public void deadLetter(UUID requestId, String reason) {
        send(MessagingConstants.ADD_MONEY_DEAD_LETTER_QUEUE, requestId, message -> {
            message.getMessageProperties().setHeader(MessagingConstants.DEAD_LETTER_REASON_HEADER, reason);
            return message;
        });
        log.warn("Add-money request dead-lettered: requestId={} reason={}", requestId, reason);
    }

    /** Publishes the request id to {@code queue} and blocks until the broker confirms it. */
    private void send(String queue, UUID requestId, MessagePostProcessor postProcessor) {
        rabbitTemplate.invoke(operations -> {
            operations.convertAndSend(MessagingConstants.DEFAULT_EXCHANGE, queue, requestId.toString(), postProcessor);
            operations.waitForConfirmsOrDie(properties.confirmTimeout().toMillis());
            return null;
        });
    }
}
