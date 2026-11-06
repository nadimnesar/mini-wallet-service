package com.pathaopay.miniwallet.client.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pathaopay.miniwallet.client.PaymentGatewayClient;
import com.pathaopay.miniwallet.client.PaymentVerification;
import com.pathaopay.miniwallet.common.constant.AppConstants.ErrorMessages;
import com.pathaopay.miniwallet.common.constant.AppConstants.Money;
import com.pathaopay.miniwallet.common.constant.AppConstants.PaymentGateway;
import com.pathaopay.miniwallet.common.exception.payment.PaymentGatewayException;
import com.pathaopay.miniwallet.common.exception.payment.PaymentNotFoundException;
import com.pathaopay.miniwallet.model.PaymentMethod;
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
 * (stubs in {@code docker/wiremock}).
 */
@Component
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
    public PaymentVerification verify(PaymentMethod method, UUID gatewayRef, long amount) {
        String network = method.name().toLowerCase(Locale.ROOT);
        VerifyResponse response;
        try {
            response = restClient.post()
                    .uri(PaymentGateway.VERIFY_PATH.formatted(network))
                    .header(PaymentGateway.API_KEY_HEADER, properties.network(method).apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new VerifyRequest(gatewayRef, amount, Money.CURRENCY_CODE))
                    .retrieve()
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.NOT_FOUND), (request, res) -> {
                        throw new PaymentNotFoundException(ErrorMessages.PAYMENT_NOT_FOUND.formatted(method, gatewayRef));
                    })
                    .body(VerifyResponse.class);
        } catch (RestClientException e) {
            // Non-2xx (other than 404), timeouts, connection failures and unreadable bodies.
            throw new PaymentGatewayException(ErrorMessages.PAYMENT_GATEWAY_ERROR.formatted(method), e);
        }
        if (response == null || response.status() == null || response.amount() == null) {
            throw new PaymentGatewayException(ErrorMessages.PAYMENT_GATEWAY_ERROR.formatted(method), null);
        }
        return new PaymentVerification(response.gatewayRef(), response.status(), response.amount(),
                response.currency(), response.cardNetwork(), response.authorizationCode(), response.declineReason());
    }

    private record VerifyRequest(
            @JsonProperty("gateway_ref") UUID gatewayRef,
            @JsonProperty("amount") long amount,
            @JsonProperty("currency") String currency) {
    }

    private record VerifyResponse(
            @JsonProperty("gateway_ref") UUID gatewayRef,
            @JsonProperty("status") PaymentVerification.Status status,
            @JsonProperty("amount") Long amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("card_network") PaymentMethod cardNetwork,
            @JsonProperty("authorization_code") String authorizationCode,
            @JsonProperty("decline_reason") String declineReason) {
    }
}
