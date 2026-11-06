package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotBlank(message = AppConstants.FROM_WALLET_ID_REQUIRED)
        @Size(min = AppConstants.ID_MIN_LENGTH, max = AppConstants.ID_MAX_LENGTH, message = AppConstants.FROM_WALLET_ID_SIZE)
        @Pattern(regexp = AppConstants.ID_PATTERN, message = AppConstants.FROM_WALLET_ID_PATTERN)
        @JsonProperty("from_wallet_id")
        String fromWalletId,

        @NotBlank(message = AppConstants.TO_WALLET_ID_REQUIRED)
        @Size(min = AppConstants.ID_MIN_LENGTH, max = AppConstants.ID_MAX_LENGTH, message = AppConstants.TO_WALLET_ID_SIZE)
        @Pattern(regexp = AppConstants.ID_PATTERN, message = AppConstants.TO_WALLET_ID_PATTERN)
        @JsonProperty("to_wallet_id")
        String toWalletId,

        @NotNull(message = AppConstants.AMOUNT_REQUIRED)
        @Min(value = AppConstants.MIN_AMOUNT_POISHA, message = AppConstants.AMOUNT_MIN)
        @Max(value = AppConstants.MAX_AMOUNT_POISHA, message = AppConstants.AMOUNT_MAX)
        @JsonProperty("amount")
        Long amount,

        @Size(max = AppConstants.NOTE_MAX_LENGTH, message = AppConstants.NOTE_SIZE)
        @JsonProperty("note")
        String note
) {}
