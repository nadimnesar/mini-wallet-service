package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record TransactionItemResponse(
        @JsonProperty("transaction_id")
        String transactionId,

        @JsonProperty("type")
        String type,

        @JsonProperty("amount")
        long amount,

        @JsonProperty("balance_after")
        long balanceAfter,

        @JsonProperty("counterparty_wallet_id")
        String counterpartyWalletId,

        @JsonProperty("reference_id")
        String referenceId,

        @JsonProperty("created_at")
        Instant createdAt
) {}
