package com.pathaopay.miniwallet.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/** Body of {@code POST /api/v1/wallets/add-money}. */
public record AddMoneyRequest(
        @NotBlank(message = AppConstants.PHONE_REQUIRED)
        @Pattern(regexp = AppConstants.PHONE_ACCEPTED_PATTERN, message = AppConstants.PHONE_FORMAT)
        @JsonProperty("phone")
        String phone,

        @NotNull(message = AppConstants.AMOUNT_REQUIRED)
        @JsonProperty("amount")
        Long amount,

        @NotNull(message = AppConstants.GATEWAY_REF_REQUIRED)
        @JsonProperty("gateway_ref")
        UUID gatewayRef,

        @NotNull(message = AppConstants.PAYMENT_METHOD_REQUIRED)
        @JsonProperty("payment_method")
        PaymentMethod paymentMethod
) {}
