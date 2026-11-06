package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record TransferResponse(
        @JsonProperty("transfer_id")
        String transferId,

        @JsonProperty("from_wallet_id")
        String fromWalletId,

        @JsonProperty("to_wallet_id")
        String toWalletId,

        @JsonProperty("amount")
        long amount,

        @JsonProperty("status")
        String status,

        @JsonProperty("created_at")
        Instant createdAt
) {}
