package com.pathaopay.miniwallet.support;

import com.pathaopay.miniwallet.common.constant.AppConstants.Database;
import com.pathaopay.miniwallet.common.constant.AppConstants.PaymentGateway;
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

/**
 * Base for tests that run the full application (on a random HTTP port) against real
 * infrastructure started by Testcontainers (Docker must be running):
 * <ul>
 *   <li>PostgreSQL, used by both the write and read pools, so read-only routing is exercised
 *   without a replica;</li>
 *   <li>the WireMock payment gateway, loaded with the same stubs as docker compose
 *   ({@code docker/wiremock}).</li>
 * </ul>
 * Containers are shared by all test classes (singleton pattern) so the cached Spring context keeps
 * pointing at live services. Every test starts with empty tables and a fresh gateway request log.
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
        for (String prefix : List.of(Database.WRITE_PROPERTIES_PREFIX, Database.READ_PROPERTIES_PREFIX)) {
            registry.add(prefix + ".jdbc-url", POSTGRES::getJdbcUrl);
            registry.add(prefix + ".username", POSTGRES::getUsername);
            registry.add(prefix + ".password", POSTGRES::getPassword);
        }
        registry.add(PaymentGateway.PROPERTIES_PREFIX + ".base-url", AbstractIntegrationTest::paymentGatewayUrl);
        // The TIMEOUT stub answers after 10s; a short read timeout keeps that test fast.
        registry.add(PaymentGateway.PROPERTIES_PREFIX + ".read-timeout", () -> "1s");
    }

    @Autowired
    protected JdbcClient jdbcClient;

    @BeforeEach
    void resetInfrastructure() {
        jdbcClient.sql("TRUNCATE TABLE idempotency_keys, wallet_transactions, wallets").update();
        RestClient.create(paymentGatewayUrl()).delete().uri("/__admin/requests").retrieve().toBodilessEntity();
    }

    protected static String paymentGatewayUrl() {
        return "http://" + PAYMENT_GATEWAY.getHost() + ":" + PAYMENT_GATEWAY.getMappedPort(8080);
    }
}
