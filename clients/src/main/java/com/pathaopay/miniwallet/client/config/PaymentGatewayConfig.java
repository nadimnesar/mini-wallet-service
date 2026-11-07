package com.pathaopay.miniwallet.client.config;

import com.pathaopay.miniwallet.client.config.properties.PaymentGatewayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link PaymentGatewayProperties}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentGatewayProperties.class)
public class PaymentGatewayConfig {
}
