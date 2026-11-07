package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/transfers}. */
public record TransferRequest(
        @NotBlank(message = AppConstants.FROM_PHONE_REQUIRED)
        @Pattern(regexp = AppConstants.PHONE_ACCEPTED_PATTERN, message = AppConstants.PHONE_FORMAT)
        @JsonProperty("from_phone")
        String fromPhone,

        @NotBlank(message = AppConstants.TO_PHONE_REQUIRED)
        @Pattern(regexp = AppConstants.PHONE_ACCEPTED_PATTERN, message = AppConstants.PHONE_FORMAT)
        @JsonProperty("to_phone")
        String toPhone,

        @NotNull(message = AppConstants.AMOUNT_REQUIRED)
        @Min(value = AppConstants.MIN_AMOUNT_POISHA, message = AppConstants.AMOUNT_MIN)
        @Max(value = AppConstants.MAX_AMOUNT_POISHA, message = AppConstants.AMOUNT_MAX)
        @JsonProperty("amount")
        Long amount,

        @Size(min = 1, max = AppConstants.NOTE_MAX_LENGTH, message = AppConstants.NOTE_SIZE)
        @JsonProperty("note")
        String note
) {}
