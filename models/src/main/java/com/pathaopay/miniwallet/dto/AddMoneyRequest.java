package com.pathaopay.miniwallet.dto;

import com.pathaopay.miniwallet.common.constant.AppConstants.Money;
import com.pathaopay.miniwallet.common.constant.AppConstants.Validation;
import com.pathaopay.miniwallet.model.PaymentMethod;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/wallets/{wallet_id}/add-money}. The wallet comes from the path. */
public record AddMoneyRequest(
        @NotNull(message = Validation.AMOUNT_REQUIRED)
        @Min(value = Money.MIN_AMOUNT_POISHA, message = Validation.AMOUNT_MIN)
        @Max(value = Money.MAX_AMOUNT_POISHA, message = Validation.AMOUNT_MAX)
        @JsonProperty("amount")
        Long amount,

        @NotBlank(message = Validation.GATEWAY_REF_REQUIRED)
        @Size(min = Validation.ID_MIN_LENGTH, max = Validation.GATEWAY_REF_MAX_LENGTH, message = Validation.GATEWAY_REF_SIZE)
        @Pattern(regexp = Validation.GATEWAY_REF_PATTERN, message = Validation.GATEWAY_REF_PATTERN_MESSAGE)
        @JsonProperty("gateway_ref")
        String gatewayRef,

        @NotNull(message = Validation.PAYMENT_METHOD_REQUIRED)
        @JsonProperty("payment_method")
        PaymentMethod paymentMethod
) {}
