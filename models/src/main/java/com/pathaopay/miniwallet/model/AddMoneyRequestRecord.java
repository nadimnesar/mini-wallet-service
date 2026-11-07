package com.pathaopay.miniwallet.model;

import com.pathaopay.miniwallet.model.enums.AddMoneyStatus;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;

import java.time.Instant;
import java.util.UUID;

/**
 * An accepted add-money request and how far it got. {@code attempts} counts processing tries (gateway calls that ended
 * in a retryable failure included); {@code transactionId} is set once COMPLETED, the error pair once FAILED (a PENDING
 * request keeps the error of its last retryable failure).
 */
public record AddMoneyRequestRecord(
        UUID id,
        UUID walletId,
        long amount,
        UUID gatewayRef,
        PaymentMethod paymentMethod,
        AddMoneyStatus status,
        int attempts,
        UUID transactionId,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {}
