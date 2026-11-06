package com.pathaopay.miniwallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.model.PaymentMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body of {@code POST /api/v1/wallets/add-money}. */
public record AddMoneyRequest(
        @NotNull(message = AppConstants.WALLET_ID_REQUIRED)
        @JsonProperty("wallet_id")
        UUID walletId,

        @NotNull(message = AppConstants.AMOUNT_REQUIRED)
        @Min(value = AppConstants.MIN_AMOUNT_POISHA, message = AppConstants.AMOUNT_MIN)
        @Max(value = AppConstants.MAX_AMOUNT_POISHA, message = AppConstants.AMOUNT_MAX)
        @JsonProperty("amount")
        Long amount,

        @NotNull(message = AppConstants.GATEWAY_REF_REQUIRED)
        @JsonProperty("gateway_ref")
        UUID gatewayRef,

        @NotNull(message = AppConstants.PAYMENT_METHOD_REQUIRED)
        @JsonProperty("payment_method")
        PaymentMethod paymentMethod
) {}
