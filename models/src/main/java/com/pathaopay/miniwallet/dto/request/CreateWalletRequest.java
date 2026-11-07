package com.pathaopay.miniwallet.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.model.enums.WalletType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/wallets}. {@code type} is the account class and has no default. The phone is any valid
 * Bangladeshi mobile number, whatever the type, and is the only uniqueness rule: one wallet per phone. {@code user_id}
 * is free: a user may own any number of wallets of any type, and no id is reserved. Any user may create any type for now;
 * role-based permissions will come later.
 */
public record CreateWalletRequest(
        @NotNull(message = AppConstants.USER_ID_REQUIRED)
        @JsonProperty("user_id")
        UUID userId,

        @NotBlank(message = AppConstants.PHONE_REQUIRED)
        @Pattern(regexp = AppConstants.PHONE_ACCEPTED_PATTERN, message = AppConstants.PHONE_FORMAT)
        @JsonProperty("phone")
        String phone,

        @NotNull(message = AppConstants.TYPE_REQUIRED)
        @JsonProperty("type")
        WalletType type
) {}
