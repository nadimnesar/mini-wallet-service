package com.pathaopay.miniwallet.client.messaging;

import com.pathaopay.miniwallet.common.constant.MessagingConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * {@code miniwallet.messaging.add-money.*}.
 *
 * @param retryDelays      the wait before each retry, in order; its size is the number of retries, so a request is
 *                         tried {@code retryDelays.size() + 1} times in all
 * @param dispatchBatchSize how many requests the dispatcher publishes per run
 * @param republishAfter   a PENDING request whose message was published longer ago than this is published again
 *                         (safety net for a lost message); keep it above the longest retry delay
 * @param confirmTimeout   how long to wait for the broker to confirm a published message
 */
@ConfigurationProperties(MessagingConstants.PROPERTIES_PREFIX)
public record AddMoneyMessagingProperties(
        List<Duration> retryDelays,
        int dispatchBatchSize,
        Duration republishAfter,
        Duration confirmTimeout
) {
    public AddMoneyMessagingProperties {
        retryDelays = retryDelays == null || retryDelays.isEmpty()
                ? List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2))
                : List.copyOf(retryDelays);
        dispatchBatchSize = dispatchBatchSize > 0 ? dispatchBatchSize : 50;
        republishAfter = republishAfter == null ? Duration.ofMinutes(10) : republishAfter;
        confirmTimeout = confirmTimeout == null ? Duration.ofSeconds(5) : confirmTimeout;
    }

    public int maxAttempts() {
        return retryDelays.size() + 1;
    }
}
