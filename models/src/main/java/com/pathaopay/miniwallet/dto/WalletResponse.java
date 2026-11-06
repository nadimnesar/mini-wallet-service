package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record WalletResponse(
        @JsonProperty("wallet_id")
        String walletId,

        @JsonProperty("user_id")
        String userId,

        @JsonProperty("currency")
        String currency,

        @JsonProperty("balance")
        long balance,

        @JsonProperty("created_at")
        Instant createdAt
) {}
