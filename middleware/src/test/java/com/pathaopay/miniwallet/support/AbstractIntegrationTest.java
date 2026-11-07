package com.pathaopay.miniwallet.support;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.DbConstants.IdempotencyKeys;
import com.pathaopay.miniwallet.common.constant.DbConstants.LedgerEntries;
import com.pathaopay.miniwallet.common.constant.DbConstants.Transactions;
import com.pathaopay.miniwallet.common.constant.DbConstants.Wallets;
import com.pathaopay.miniwallet.common.constant.PaymentGatewayConstants;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Base for tests that run the full application (on a random HTTP port) against real
 * infrastructure started by Testcontainers (Docker must be running):
 * <ul>
 *   <li>PostgreSQL;</li>
 *   <li>the WireMock payment gateway, loaded with the same stubs as docker compose
 *   ({@code docker/wiremock}).</li>
 * </ul>
 * Containers are shared by all test classes (singleton pattern) so the cached Spring context keeps
 * pointing at live services. Every test starts with empty tables (except the seeded system accounts) and a fresh gateway request log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    protected static final GenericContainer<?> PAYMENT_GATEWAY = new GenericContainer<>("wiremock/wiremock:3.13.2")
            .withExposedPorts(8080)
            .withCommand("--port", "8080", "--disable-banner")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "docker", "wiremock")), "/home/wiremock")
            .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

    static {
        POSTGRES.start();
        PAYMENT_GATEWAY.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(PaymentGatewayConstants.PROPERTIES_PREFIX + ".base-url", AbstractIntegrationTest::paymentGatewayUrl);
        // The TIMEOUT stub answers after 10s; a short read timeout keeps that test fast.
        registry.add(PaymentGatewayConstants.PROPERTIES_PREFIX + ".read-timeout", () -> "1s");
    }

    private static final List<UUID> SYSTEM_WALLETS = List.of(AppConstants.REVENUE_WALLET_ID,
            AppConstants.GATEWAY_CLEARING_WALLET_ID,
            AppConstants.OPERATING_EXPENSE_WALLET_ID, AppConstants.OWNER_EQUITY_WALLET_ID);

    @Autowired
    protected JdbcClient jdbcClient;

    @BeforeEach
    void resetInfrastructure() {
        jdbcClient.sql("TRUNCATE TABLE " + String.join(", ", IdempotencyKeys.TABLE, LedgerEntries.TABLE,
                Transactions.TABLE)).update();
        // The system accounts are seeded by Liquibase (all at balance 0), so they survive the reset.
        jdbcClient.sql("DELETE FROM " + Wallets.TABLE + " WHERE " + Wallets.WALLET_ID + " NOT IN (:system)")
                .param("system", SYSTEM_WALLETS).update();
        RestClient.create(paymentGatewayUrl()).delete().uri("/__admin/requests").retrieve().toBodilessEntity();
    }

    protected static String paymentGatewayUrl() {
        return "http://" + PAYMENT_GATEWAY.getHost() + ":" + PAYMENT_GATEWAY.getMappedPort(8080);
    }
}
