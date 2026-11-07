package com.pathaopay.miniwallet.common.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * RabbitMQ topology for asynchronous add-money. Everything uses the default exchange, so a queue name is its routing
 * key: a retry queue holds a message for its TTL and then dead-letters it back onto the work queue.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MessagingConstants {
    /** Prefix of the {@code application.properties} keys for add-money messaging. */
    public static final String PROPERTIES_PREFIX = "miniwallet.messaging.add-money";
    /** The default (nameless) exchange, which routes by queue name. */
    public static final String DEFAULT_EXCHANGE = "";
    /** Work queue consumed by the add-money listener. */
    public static final String ADD_MONEY_QUEUE = "wallet.add-money";
    /** Format with the retry number (1 = first retry). */
    public static final String ADD_MONEY_RETRY_QUEUE = "wallet.add-money.retry.%d";
    /** Receives the ids of requests that ran out of attempts. */
    public static final String ADD_MONEY_DEAD_LETTER_QUEUE = "wallet.add-money.dlq";
    /** Message header that holds why a request was dead-lettered. */
    public static final String DEAD_LETTER_REASON_HEADER = "x-failure-reason";
}
