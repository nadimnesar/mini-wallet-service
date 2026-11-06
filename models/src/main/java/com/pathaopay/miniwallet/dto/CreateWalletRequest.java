package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record CreateWalletRequest(
        @NotNull(message = AppConstants.USER_ID_REQUIRED)
        @JsonProperty("user_id")
        UUID userId,

        @NotBlank(message = AppConstants.PHONE_REQUIRED)
        @Pattern(regexp = AppConstants.PHONE_ACCEPTED_PATTERN, message = AppConstants.PHONE_FORMAT)
        @JsonProperty("phone")
        String phone
) {}
