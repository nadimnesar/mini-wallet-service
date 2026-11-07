package com.pathaopay.miniwallet.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.model.enums.EntryDirection;
import com.pathaopay.miniwallet.model.enums.EntryType;

import java.time.Instant;
import java.util.UUID;

/** One ledger entry (a debit or credit leg) of a transaction. */
public record TransactionItemResponse(
        @JsonProperty("entry_id")
        UUID entryId,

        @JsonProperty("transaction_id")
        UUID transactionId,

        @JsonProperty("wallet_id")
        UUID walletId,

        @JsonProperty("phone")
        String phone,

        @JsonProperty("type")
        EntryType type,

        @JsonProperty("direction")
        EntryDirection direction,

        @JsonProperty("amount")
        long amount,

        @JsonProperty("balance_after")
        long balanceAfter,

        @JsonProperty("counterparty_wallet_id")
        UUID counterpartyWalletId,

        @JsonProperty("counterparty_phone")
        String counterpartyPhone,

        @JsonProperty("created_at")
        Instant createdAt
) {}
