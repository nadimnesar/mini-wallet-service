package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.model.Currency;
import com.pathaopay.miniwallet.model.WalletType;
import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        @JsonProperty("wallet_id")
        UUID walletId,

        @JsonProperty("user_id")
        UUID userId,

        @JsonProperty("phone")
        String phone,

        @JsonProperty("currency")
        Currency currency,

        @JsonProperty("type")
        WalletType type,

        @JsonProperty("created_at")
        Instant createdAt
) {}
