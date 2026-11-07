package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.model.PaymentMethod;
import com.pathaopay.miniwallet.model.TransactionType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A transaction with all of its ledger entries; their debits equal their credits. */
public record TransactionDetailResponse(
        @JsonProperty("transaction_id")
        UUID transactionId,

        @JsonProperty("type")
        TransactionType type,

        @JsonProperty("amount")
        long amount,

        @JsonProperty("gateway_ref")
        UUID gatewayRef,

        @JsonProperty("payment_method")
        PaymentMethod paymentMethod,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("entries")
        List<TransactionItemResponse> entries
) {}
