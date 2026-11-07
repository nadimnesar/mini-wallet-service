package com.pathaopay.miniwallet.client.messaging;

import com.pathaopay.miniwallet.common.constant.MessagingConstants;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares the queues (RabbitAdmin creates them on connect):
 * <ul>
 *   <li>{@code wallet.add-money}: the work queue. A message the listener rejects without requeueing (an unexpected
 *   error) is dead-lettered to the DLQ;</li>
 *   <li>{@code wallet.add-money.retry.N}: one queue per retry delay, with that delay as its TTL and the work queue as
 *   its dead-letter target, so an expired message flows back to be tried again without any consumer;</li>
 *   <li>{@code wallet.add-money.dlq}: where exhausted or poisoned requests end up.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AddMoneyMessagingProperties.class)
public class RabbitMqConfig {

    @Bean
    Declarables addMoneyQueues(AddMoneyMessagingProperties properties) {
        List<Declarable> queues = new ArrayList<>();
        queues.add(QueueBuilder.durable(MessagingConstants.ADD_MONEY_QUEUE)
                .deadLetterExchange(MessagingConstants.DEFAULT_EXCHANGE)
                .deadLetterRoutingKey(MessagingConstants.ADD_MONEY_DEAD_LETTER_QUEUE)
                .build());
        queues.add(QueueBuilder.durable(MessagingConstants.ADD_MONEY_DEAD_LETTER_QUEUE).build());
        for (int retry = 1; retry <= properties.retryDelays().size(); retry++) {
            queues.add(retryQueue(retry, properties.retryDelays().get(retry - 1).toMillis()));
        }
        return new Declarables(queues);
    }

    private static Queue retryQueue(int retry, long delayMillis) {
        return QueueBuilder.durable(MessagingConstants.ADD_MONEY_RETRY_QUEUE.formatted(retry))
                .ttl((int) delayMillis)
                .deadLetterExchange(MessagingConstants.DEFAULT_EXCHANGE)
                .deadLetterRoutingKey(MessagingConstants.ADD_MONEY_QUEUE)
                .build();
    }
}
