package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.common.DuplicatePhoneException;
import com.pathaopay.miniwallet.common.DuplicateWalletException;
import com.pathaopay.miniwallet.common.InvalidPhoneNumberException;
import com.pathaopay.miniwallet.common.WalletNotFoundException;
import com.pathaopay.miniwallet.common.constant.AppConstants.Database;
import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;
import com.pathaopay.miniwallet.model.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real wallet service, JDBC repository and Liquibase schema against a real PostgreSQL
 * started by Testcontainers (Docker must be running). Both the write and read pools point at the
 * same container, so read-only routing is exercised without needing a replica.
 */
@SpringBootTest
@Testcontainers
class WalletServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        for (String prefix : List.of(Database.WRITE_PROPERTIES_PREFIX, Database.READ_PROPERTIES_PREFIX)) {
            registry.add(prefix + ".jdbc-url", POSTGRES::getJdbcUrl);
            registry.add(prefix + ".username", POSTGRES::getUsername);
            registry.add(prefix + ".password", POSTGRES::getPassword);
        }
    }

    @Autowired
    private WalletService walletService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE wallets").update();
    }

    @Test
    void createsZeroBalanceBdtWalletAndStoresNormalizedPhone() {
        WalletResponse response = walletService.createWallet(new CreateWalletRequest("u_1001", "+8801711000001"));

        assertEquals(7, response.walletId().version());
        assertEquals(2, response.walletId().variant());
        assertEquals("u_1001", response.userId());
        assertEquals("01711000001", response.phone());
        assertEquals(Currency.BDT, response.currency());
        assertEquals(0, response.balance());
        assertEquals("01711000001", storedPhone(response.walletId()));
    }

    @Test
    void rejectsSecondWalletForSameUser() {
        walletService.createWallet(new CreateWalletRequest("u_1001", "01711000001"));

        assertThrows(DuplicateWalletException.class,
                () -> walletService.createWallet(new CreateWalletRequest("u_1001", "01811000002")));
        assertEquals(1, walletCount());
    }

    @Test
    void rejectsSamePhoneForAnotherUserInAnyFormat() {
        walletService.createWallet(new CreateWalletRequest("u_1001", "01711000001"));

        for (String samePhone : List.of("01711000001", "1711000001", "8801711000001", "+8801711000001")) {
            DuplicatePhoneException ex = assertThrows(DuplicatePhoneException.class,
                    () -> walletService.createWallet(new CreateWalletRequest("u_other", samePhone)));
            assertEquals("A wallet already exists for phone: 01711000001", ex.getMessage());
        }
        assertEquals(1, walletCount());
    }

    @Test
    void sameUserAndSamePhoneReportsDuplicateWallet() {
        walletService.createWallet(new CreateWalletRequest("u_1001", "01711000001"));

        assertThrows(DuplicateWalletException.class,
                () -> walletService.createWallet(new CreateWalletRequest("u_1001", "+8801711000001")));
    }

    @Test
    void rejectsInvalidPhoneWithoutWritingAnything() {
        assertThrows(InvalidPhoneNumberException.class,
                () -> walletService.createWallet(new CreateWalletRequest("u_1001", "12345")));
        assertEquals(0, walletCount());
    }

    @Test
    void getsExistingWallet() {
        WalletResponse created = walletService.createWallet(new CreateWalletRequest("u_1001", "1711000001"));

        assertEquals(created, walletService.getWallet(created.walletId()));
    }

    @Test
    void throwsWhenWalletMissing() {
        assertThrows(WalletNotFoundException.class, () -> walletService.getWallet(UUID.randomUUID()));
    }

    @Test
    void concurrentCreatesForSameUserProduceExactlyOneWallet() throws Exception {
        int attempts = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WalletResponse>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return walletService.createWallet(new CreateWalletRequest("u_race", "01911000009"));
                }));
            }
            start.countDown();
        }

        int created = 0;
        int duplicates = 0;
        for (Future<WalletResponse> result : results) {
            try {
                result.get();
                created++;
            } catch (ExecutionException e) {
                assertInstanceOf(DuplicateWalletException.class, e.getCause());
                duplicates++;
            }
        }
        assertEquals(1, created);
        assertEquals(attempts - 1, duplicates);
        assertEquals(1, walletCount());
    }

    @Test
    void concurrentCreatesForSamePhoneByDifferentUsersProduceExactlyOneWallet() throws Exception {
        int attempts = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WalletResponse>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                String userId = "u_phone_race_" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    return walletService.createWallet(new CreateWalletRequest(userId, "+8801911000009"));
                }));
            }
            start.countDown();
        }

        int created = 0;
        for (Future<WalletResponse> result : results) {
            try {
                result.get();
                created++;
            } catch (ExecutionException e) {
                assertInstanceOf(DuplicatePhoneException.class, e.getCause());
            }
        }
        assertEquals(1, created);
        assertEquals(1, walletCount());
    }

    @Test
    void databaseRejectsNegativeBalance() {
        WalletResponse wallet = walletService.createWallet(new CreateWalletRequest("u_1001", "01711000001"));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient
                .sql("UPDATE wallets SET balance = -1 WHERE id = :id")
                .param("id", wallet.walletId())
                .update());
    }

    private String storedPhone(UUID walletId) {
        return jdbcClient.sql("SELECT phone FROM wallets WHERE id = :id")
                .param("id", walletId)
                .query(String.class)
                .single();
    }

    private long walletCount() {
        return jdbcClient.sql("SELECT count(*) FROM wallets").query(Long.class).single();
    }
}
