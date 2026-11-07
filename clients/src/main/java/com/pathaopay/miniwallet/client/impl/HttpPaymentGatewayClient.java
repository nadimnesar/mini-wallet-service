package com.pathaopay.miniwallet.client.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.client.PaymentGatewayClient;
import com.pathaopay.miniwallet.client.config.properties.PaymentGatewayProperties;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.ErrorConstants.ErrorMessage;
import com.pathaopay.miniwallet.common.constant.PaymentGatewayConstants;
import com.pathaopay.miniwallet.common.exception.payment.PaymentGatewayException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentNotFoundException;
import com.pathaopay.miniwallet.model.PaymentVerification;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.util.Locale;
import java.util.UUID;

/**
 * Calls the card network's gateway over HTTP: {@code POST {base-url}/{visa|mastercard}/v1/payments/verify}
 * with that network's API key. Locally and in tests the gateway is a WireMock server
 * (stubs in {@code wiremock}).
 */
@Component
@Slf4j
public class HttpPaymentGatewayClient implements PaymentGatewayClient {

    private final RestClient restClient;
    private final PaymentGatewayProperties properties;

    public HttpPaymentGatewayClient(PaymentGatewayProperties properties) {
        this.properties = properties;
        // HTTP/1.1: the JDK client otherwise attempts an h2c upgrade, which gateways (and WireMock's
        // Jetty) may reset for POST requests with a body.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public PaymentVerification verify(PaymentMethod method, UUID gatewayRef, String merchantReference, long amount) {
        String network = method.name().toLowerCase(Locale.ROOT);
        VerifyResponse response;
        log.debug("Verifying payment with {} gateway: gatewayRef={} amount={}", method, gatewayRef, amount);
        try {
            response = restClient.post()
                    .uri(PaymentGatewayConstants.VERIFY_PATH.formatted(network))
                    .header(PaymentGatewayConstants.API_KEY_HEADER, properties.network(method).apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new VerifyRequest(gatewayRef, merchantReference, amount, AppConstants.CURRENCY_CODE))
                    .retrieve()
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.NOT_FOUND), (request, res) -> {
                        log.info("{} gateway has no payment: gatewayRef={}", method, gatewayRef);
                        throw new PaymentNotFoundException(ErrorMessage.PAYMENT_NOT_FOUND.formatted(method, gatewayRef));
                    })
                    .body(VerifyResponse.class);
        } catch (RestClientException e) {
            // Non-2xx (other than 404), timeouts, connection failures and unreadable bodies.
            log.warn("{} gateway call failed: gatewayRef={} cause={}", method, gatewayRef, e.getMessage());
            throw new PaymentGatewayException(ErrorMessage.PAYMENT_GATEWAY_ERROR.formatted(method), e);
        }
        if (response == null || response.status() == null || response.amount() == null) {
            log.warn("{} gateway returned an incomplete response: gatewayRef={}", method, gatewayRef);
            throw new PaymentGatewayException(ErrorMessage.PAYMENT_GATEWAY_ERROR.formatted(method), null);
        }
        log.info("{} gateway verified payment: gatewayRef={} status={} declineReason={}",
                method, gatewayRef, response.status(), response.declineReason());
        return new PaymentVerification(response.gatewayRef(), response.status(), response.amount(),
                response.currency(), response.cardNetwork(), response.declineReason(),
                response.merchantReference());
    }

    private record VerifyRequest(
            @JsonProperty("gateway_ref") UUID gatewayRef,
            @JsonProperty("merchant_reference") String merchantReference,
            @JsonProperty("amount") long amount,
            @JsonProperty("currency") String currency) {
    }

    private record VerifyResponse(
            @JsonProperty("gateway_ref") UUID gatewayRef,
            @JsonProperty("status") PaymentVerification.Status status,
            @JsonProperty("amount") Long amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("card_network") PaymentMethod cardNetwork,
            @JsonProperty("decline_reason") String declineReason,
            @JsonProperty("merchant_reference") String merchantReference) {
    }
}
