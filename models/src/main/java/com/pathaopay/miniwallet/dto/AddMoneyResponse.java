package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record AddMoneyResponse(
        @JsonProperty("wallet_id")
        String walletId,

        @JsonProperty("balance")
        long balance,

        @JsonProperty("added_amount")
        long addedAmount,

        @JsonProperty("gateway_ref")
        String gatewayRef,

        @JsonProperty("created_at")
        Instant createdAt
) {}
