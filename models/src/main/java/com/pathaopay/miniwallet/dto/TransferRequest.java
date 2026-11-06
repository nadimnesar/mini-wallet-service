package com.pathaopay.miniwallet.dto;

import com.pathaopay.miniwallet.common.constant.AppConstants.Money;
import com.pathaopay.miniwallet.common.constant.AppConstants.Validation;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotBlank(message = Validation.FROM_WALLET_ID_REQUIRED)
        @Size(min = Validation.ID_MIN_LENGTH, max = Validation.ID_MAX_LENGTH, message = Validation.FROM_WALLET_ID_SIZE)
        @Pattern(regexp = Validation.ID_PATTERN, message = Validation.FROM_WALLET_ID_PATTERN)
        @JsonProperty("from_wallet_id")
        String fromWalletId,

        @NotBlank(message = Validation.TO_WALLET_ID_REQUIRED)
        @Size(min = Validation.ID_MIN_LENGTH, max = Validation.ID_MAX_LENGTH, message = Validation.TO_WALLET_ID_SIZE)
        @Pattern(regexp = Validation.ID_PATTERN, message = Validation.TO_WALLET_ID_PATTERN)
        @JsonProperty("to_wallet_id")
        String toWalletId,

        @NotNull(message = Validation.AMOUNT_REQUIRED)
        @Min(value = Money.MIN_AMOUNT_POISHA, message = Validation.AMOUNT_MIN)
        @Max(value = Money.MAX_AMOUNT_POISHA, message = Validation.AMOUNT_MAX)
        @JsonProperty("amount")
        Long amount,

        @Size(max = Validation.NOTE_MAX_LENGTH, message = Validation.NOTE_SIZE)
        @JsonProperty("note")
        String note
) {}
