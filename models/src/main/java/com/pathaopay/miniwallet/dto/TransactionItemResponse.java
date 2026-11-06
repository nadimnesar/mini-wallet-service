package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.model.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record TransactionItemResponse(
        @JsonProperty("transaction_id")
        UUID transactionId,

        @JsonProperty("type")
        TransactionType type,

        @JsonProperty("amount")
        long amount,

        @JsonProperty("balance_after")
        long balanceAfter,

        @JsonProperty("counterparty_wallet_id")
        UUID counterpartyWalletId,

        @JsonProperty("reference_id")
        UUID referenceId,

        @JsonProperty("created_at")
        Instant createdAt
) {}
