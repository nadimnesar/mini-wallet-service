package com.pathaopay.miniwallet.dto;

import com.pathaopay.miniwallet.common.constant.AppConstants.Phone;
import com.pathaopay.miniwallet.common.constant.AppConstants.Validation;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateWalletRequest(
        @NotBlank(message = Validation.USER_ID_REQUIRED)
        @JsonProperty("user_id")
        String userId,

        @NotBlank(message = Validation.PHONE_REQUIRED)
        @Pattern(regexp = Phone.ACCEPTED_PATTERN, message = Validation.PHONE_FORMAT)
        @JsonProperty("phone")
        String phone
) {}
