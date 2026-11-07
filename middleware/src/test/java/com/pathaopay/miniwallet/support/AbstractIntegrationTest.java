package com.pathaopay.miniwallet.support;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.DbConstants.AddMoneyRequests;
import com.pathaopay.miniwallet.common.constant.DbConstants.IdempotencyKeys;
import com.pathaopay.miniwallet.common.constant.DbConstants.LedgerEntries;
import com.pathaopay.miniwallet.common.constant.DbConstants.Transactions;
import com.pathaopay.miniwallet.common.constant.DbConstants.Wallets;
import com.pathaopay.miniwallet.common.constant.MessagingConstants;
import com.pathaopay.miniwallet.common.constant.PaymentGatewayConstants;
import com.pathaopay.miniwallet.dto.request.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.response.AddMoneyStatusResponse;
import com.pathaopay.miniwallet.model.enums.AddMoneyStatus;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;
import com.pathaopay.miniwallet.service.TransactionService;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * Base for tests that run the full application (on a random HTTP port) against real
 * infrastructure started by Testcontainers (Docker must be running):
 * <ul>
 *   <li>PostgreSQL;</li>
 *   <li>RabbitMQ, with short retry delays (300 ms) so the retry tests are quick;</li>
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

    private static final GenericContainer<?> RABBITMQ = new GenericContainer<>("rabbitmq:4-alpine")
            .withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER", "miniwallet")
            .withEnv("RABBITMQ_DEFAULT_PASS", "miniwallet")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    /** Retries after 300 ms each: an add-money request is tried 3 times. */
    protected static final int ADD_MONEY_MAX_ATTEMPTS = 3;

    static {
        POSTGRES.start();
        PAYMENT_GATEWAY.start();
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // A short lock timeout keeps the lock-contention tests fast (production: 10 s).
        registry.add("spring.datasource.hikari.data-source-properties.options",
                () -> "-c lock_timeout=2000 -c idle_in_transaction_session_timeout=60000");
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(5672));
        registry.add(MessagingConstants.PROPERTIES_PREFIX + ".retry-delays", () -> "300ms,300ms");
        registry.add(MessagingConstants.PROPERTIES_PREFIX + ".dispatch-interval-ms", () -> "100");
        registry.add(PaymentGatewayConstants.PROPERTIES_PREFIX + ".base-url", AbstractIntegrationTest::paymentGatewayUrl);
        // The TIMEOUT stub answers after 10s; a short read timeout keeps that test fast.
        registry.add(PaymentGatewayConstants.PROPERTIES_PREFIX + ".read-timeout", () -> "1s");
    }

    private static final List<UUID> SYSTEM_WALLETS = List.of(AppConstants.REVENUE_WALLET_ID,
            AppConstants.GATEWAY_CLEARING_WALLET_ID);

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected TransactionService transactionService;

    @Autowired
    private javax.sql.DataSource dataSource;

    /**
     * Locks a wallet row (SELECT ... FOR UPDATE) from a separate transaction, like a stuck money movement would.
     * Close the result to roll it back and release the lock.
     */
    protected AutoCloseable holdRowLock(UUID walletId) throws Exception {
        java.sql.Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM " + Wallets.TABLE + " WHERE " + Wallets.WALLET_ID + " = ? FOR UPDATE")) {
            statement.setObject(1, walletId);
            statement.execute();
        }
        return () -> {
            connection.rollback();
            connection.close();
        };
    }

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void resetInfrastructure() {
        // Drop in-flight messages first, so nothing from the previous test touches this one's rows.
        purgeAddMoneyQueues();
        jdbcClient.sql("TRUNCATE TABLE " + String.join(", ", AddMoneyRequests.TABLE, IdempotencyKeys.TABLE,
                LedgerEntries.TABLE, Transactions.TABLE)).update();
        // The system accounts are seeded by Liquibase (all at balance 0), so they survive the reset.
        jdbcClient.sql("DELETE FROM " + Wallets.TABLE + " WHERE " + Wallets.WALLET_ID + " NOT IN (:system)")
                .param("system", SYSTEM_WALLETS).update();
        RestClient.create(paymentGatewayUrl()).delete().uri("/__admin/requests").retrieve().toBodilessEntity();
        RestClient.create(paymentGatewayUrl()).post().uri("/__admin/scenarios/reset").retrieve().toBodilessEntity();
    }

    private void purgeAddMoneyQueues() {
        rabbitAdmin.purgeQueue(MessagingConstants.ADD_MONEY_QUEUE, false);
        rabbitAdmin.purgeQueue(MessagingConstants.ADD_MONEY_DEAD_LETTER_QUEUE, false);
        for (int retry = 1; retry < ADD_MONEY_MAX_ATTEMPTS; retry++) {
            rabbitAdmin.purgeQueue(MessagingConstants.ADD_MONEY_RETRY_QUEUE.formatted(retry), false);
        }
    }

    /** Messages waiting on the dead-letter queue. */
    protected long deadLetters() {
        var info = rabbitAdmin.getQueueInfo(MessagingConstants.ADD_MONEY_DEAD_LETTER_QUEUE);
        return info == null ? 0 : info.getMessageCount();
    }

    /** Waits until the request is COMPLETED or FAILED (the broker and the worker do the work) and returns it. */
    protected AddMoneyStatusResponse awaitFinished(UUID requestId) {
        AddMoneyStatusResponse[] last = new AddMoneyStatusResponse[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
            last[0] = transactionService.getAddMoneyRequest(requestId);
            return last[0].status() != AddMoneyStatus.PENDING;
        });
        return last[0];
    }

    /** Credits a wallet end to end: accepts an add-money request and waits for the worker to complete it. */
    protected AddMoneyStatusResponse topUp(String phone, long amount, String idempotencyKey) {
        AddMoneyStatusResponse accepted = transactionService.addMoney(
                new AddMoneyRequest(phone, amount, UUID.randomUUID(), PaymentMethod.VISA), idempotencyKey);
        AddMoneyStatusResponse finished = awaitFinished(accepted.requestId());
        if (finished.status() != AddMoneyStatus.COMPLETED) {
            throw new AssertionError("Top-up did not complete: " + finished);
        }
        return finished;
    }

    protected static String paymentGatewayUrl() {
        return "http://" + PAYMENT_GATEWAY.getHost() + ":" + PAYMENT_GATEWAY.getMappedPort(8080);
    }
}
