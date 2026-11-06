package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotBlank(message = "user_id is required")
        @Size(min = 1, max = 64, message = "user_id must be between 1 and 64 characters")
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "user_id must contain only alphanumeric characters, underscores, dashes, or dots")
        @JsonProperty("user_id")
        String userId,

        @NotBlank(message = "phone is required")
        @Pattern(regexp = "^01\\d{9}$", message = "phone must match the pattern ^01\\d{9}$")
        @JsonProperty("phone")
        String phone
) {}
