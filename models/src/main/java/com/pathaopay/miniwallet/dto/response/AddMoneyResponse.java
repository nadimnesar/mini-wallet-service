package com.pathaopay.miniwallet.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;

import java.time.Instant;
import java.util.UUID;

/** Built entirely from the immutable ledger entry, so an idempotent replay returns the same body. */
public record AddMoneyResponse(
        @JsonProperty("transaction_id")
        UUID transactionId,

        @JsonProperty("wallet_id")
        UUID walletId,

        @JsonProperty("phone")
        String phone,

        @JsonProperty("added_amount")
        long addedAmount,

        @JsonProperty("balance")
        long balance,

        @JsonProperty("gateway_ref")
        UUID gatewayRef,

        @JsonProperty("payment_method")
        PaymentMethod paymentMethod,

        @JsonProperty("created_at")
        Instant createdAt
) {}
