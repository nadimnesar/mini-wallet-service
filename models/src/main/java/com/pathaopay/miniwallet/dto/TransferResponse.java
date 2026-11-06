package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** Built from the sender's TRANSFER_OUT ledger entry, so an idempotent replay returns the same body. */
public record TransferResponse(
        @JsonProperty("transfer_id")
        UUID transferId,

        @JsonProperty("from_wallet_id")
        UUID fromWalletId,

        @JsonProperty("to_wallet_id")
        UUID toWalletId,

        @JsonProperty("amount")
        long amount,

        @JsonProperty("status")
        String status,

        @JsonProperty("created_at")
        Instant createdAt
) {}
