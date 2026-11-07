package com.pathaopay.miniwallet.listener;

import com.pathaopay.miniwallet.common.constant.MessagingConstants;
import com.pathaopay.miniwallet.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes the add-money work queue. The message is the request id; retries, final failures and the broker calls that
 * follow them are handled by {@link TransactionService#processAddMoney}, which also counts unexpected errors as
 * attempts. An exception that still escapes here means the outcome could not be recorded (database or broker down): the
 * container does not requeue it (see application.properties), so the broker dead-letters it, and the dispatcher
 * republishes the still-PENDING request later.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AddMoneyListener {

    private final TransactionService transactionService;

    /** Handles one add-money message by processing the request with that id. */
    @RabbitListener(queues = MessagingConstants.ADD_MONEY_QUEUE)
    public void onMessage(String requestId) {
        log.debug("Received add-money message: requestId={}", requestId);
        transactionService.processAddMoney(UUID.fromString(requestId));
    }
}
