package com.pathaopay.miniwallet.client.config.properties;

import com.pathaopay.miniwallet.common.constant.PaymentGatewayConstants;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** {@code miniwallet.payment-gateway.*}: where the gateway lives and each network's credentials. */
@ConfigurationProperties(PaymentGatewayConstants.PROPERTIES_PREFIX)
public record PaymentGatewayProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Network visa,
        Network mastercard
) {
    public record Network(String apiKey) {
    }

    public Network network(PaymentMethod method) {
        return switch (method) {
            case VISA -> visa;
            case MASTERCARD -> mastercard;
        };
    }
}
