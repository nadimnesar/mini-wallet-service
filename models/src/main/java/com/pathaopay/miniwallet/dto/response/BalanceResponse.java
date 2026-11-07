package com.pathaopay.miniwallet.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.model.enums.Currency;

import java.time.Instant;
import java.util.UUID;

/** A wallet's current balance in poisha, as returned by the balance endpoint. */
public record BalanceResponse(
        @JsonProperty("wallet_id")
        UUID walletId,

        @JsonProperty("phone")
        String phone,

        @JsonProperty("currency")
        Currency currency,

        @JsonProperty("balance")
        long balance,

        /** When the balance last changed (the latest ledger entry); the wallet's creation time if it never has. */
        @JsonProperty("updated_at")
        Instant updatedAt
) {}
