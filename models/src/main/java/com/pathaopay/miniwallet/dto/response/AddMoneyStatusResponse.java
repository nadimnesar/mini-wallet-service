package com.pathaopay.miniwallet.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.model.enums.AddMoneyStatus;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;

import java.time.Instant;
import java.util.UUID;

/**
 * The current state of an add-money request. {@code transaction_id} and {@code balance} (the wallet balance right
 * after the credit) appear once COMPLETED; {@code error_code} and {@code error_message} once FAILED, or on a PENDING
 * request that is waiting for a retry (the last retryable failure).
 */
public record AddMoneyStatusResponse(
        @JsonProperty("request_id")
        UUID requestId,

        @JsonProperty("status")
        AddMoneyStatus status,

        @JsonProperty("wallet_id")
        UUID walletId,

        @JsonProperty("phone")
        String phone,

        @JsonProperty("amount")
        long amount,

        @JsonProperty("gateway_ref")
        UUID gatewayRef,

        @JsonProperty("payment_method")
        PaymentMethod paymentMethod,

        @JsonProperty("attempts")
        int attempts,

        @JsonProperty("transaction_id")
        UUID transactionId,

        @JsonProperty("balance")
        Long balance,

        @JsonProperty("error_code")
        String errorCode,

        @JsonProperty("error_message")
        String errorMessage,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("updated_at")
        Instant updatedAt
) {}
