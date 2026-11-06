package com.pathaopay.miniwallet.dto;

import com.pathaopay.miniwallet.common.constant.AppConstants.Money;
import com.pathaopay.miniwallet.common.constant.AppConstants.Validation;
import com.pathaopay.miniwallet.model.PaymentMethod;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body of {@code POST /api/v1/wallets/add-money}. */
public record AddMoneyRequest(
        @NotNull(message = Validation.WALLET_ID_REQUIRED)
        @JsonProperty("wallet_id")
        UUID walletId,

        @NotNull(message = Validation.AMOUNT_REQUIRED)
        @Min(value = Money.MIN_AMOUNT_POISHA, message = Validation.AMOUNT_MIN)
        @Max(value = Money.MAX_AMOUNT_POISHA, message = Validation.AMOUNT_MAX)
        @JsonProperty("amount")
        Long amount,

        @NotNull(message = Validation.GATEWAY_REF_REQUIRED)
        @JsonProperty("gateway_ref")
        UUID gatewayRef,

        @NotNull(message = Validation.PAYMENT_METHOD_REQUIRED)
        @JsonProperty("payment_method")
        PaymentMethod paymentMethod
) {}
