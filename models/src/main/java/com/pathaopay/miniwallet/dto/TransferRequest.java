package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Body of {@code POST /api/v1/transfers}. */
public record TransferRequest(
        @NotNull(message = AppConstants.FROM_WALLET_ID_REQUIRED)
        @JsonProperty("from_wallet_id")
        UUID fromWalletId,

        @NotNull(message = AppConstants.TO_WALLET_ID_REQUIRED)
        @JsonProperty("to_wallet_id")
        UUID toWalletId,

        @NotNull(message = AppConstants.AMOUNT_REQUIRED)
        @Min(value = AppConstants.MIN_AMOUNT_POISHA, message = AppConstants.AMOUNT_MIN)
        @Max(value = AppConstants.MAX_AMOUNT_POISHA, message = AppConstants.AMOUNT_MAX)
        @JsonProperty("amount")
        Long amount,

        @Size(max = AppConstants.NOTE_MAX_LENGTH, message = AppConstants.NOTE_SIZE)
        @JsonProperty("note")
        String note
) {}
