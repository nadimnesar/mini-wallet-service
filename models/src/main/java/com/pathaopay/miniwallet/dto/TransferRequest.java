package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotBlank(message = "from_wallet_id is required")
        @Size(min = 1, max = 64, message = "from_wallet_id must be between 1 and 64 characters")
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "from_wallet_id must contain only alphanumeric characters, underscores, dashes, or dots")
        @JsonProperty("from_wallet_id")
        String fromWalletId,

        @NotBlank(message = "to_wallet_id is required")
        @Size(min = 1, max = 64, message = "to_wallet_id must be between 1 and 64 characters")
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "to_wallet_id must contain only alphanumeric characters, underscores, dashes, or dots")
        @JsonProperty("to_wallet_id")
        String toWalletId,

        @NotNull(message = "amount is required")
        @Min(value = 100, message = "amount must be at least 100 poisha (1 BDT)")
        @Max(value = 2500000, message = "amount must be at most 2500000 poisha (25000 BDT)")
        @JsonProperty("amount")
        Long amount,

        @Size(max = 255, message = "note must not exceed 255 characters")
        @JsonProperty("note")
        String note
) {}
