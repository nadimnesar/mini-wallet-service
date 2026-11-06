package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.common.exception.wallet.DuplicatePhoneException;
import com.pathaopay.miniwallet.common.exception.wallet.DuplicateWalletException;
import com.pathaopay.miniwallet.common.exception.wallet.InvalidPhoneNumberException;
import com.pathaopay.miniwallet.common.exception.wallet.WalletNotFoundException;
import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;
import com.pathaopay.miniwallet.model.Currency;
import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
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

/** Runs the real wallet service, JDBC repository and Liquibase schema against real PostgreSQL. */
class WalletServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WalletService walletService;

    @Test
    void createsZeroBalanceBdtWalletAndStoresNormalizedPhone() {
        WalletResponse response = walletService.createWallet(new CreateWalletRequest(user("u_1001"), "+8801711000001"));

        assertEquals(7, response.walletId().version());
        assertEquals(2, response.walletId().variant());
        assertEquals(user("u_1001"), response.userId());
        assertEquals("01711000001", response.phone());
        assertEquals(Currency.BDT, response.currency());
        assertEquals(0, response.balance());
        assertEquals("01711000001", storedPhone(response.walletId()));
    }

    @Test
    void rejectsSecondWalletForSameUser() {
        walletService.createWallet(new CreateWalletRequest(user("u_1001"), "01711000001"));

        assertThrows(DuplicateWalletException.class,
                () -> walletService.createWallet(new CreateWalletRequest(user("u_1001"), "01811000002")));
        assertEquals(1, walletCount());
    }

    @Test
    void rejectsSamePhoneForAnotherUserInAnyFormat() {
        walletService.createWallet(new CreateWalletRequest(user("u_1001"), "01711000001"));

        for (String samePhone : List.of("01711000001", "1711000001", "8801711000001", "+8801711000001")) {
            DuplicatePhoneException ex = assertThrows(DuplicatePhoneException.class,
                    () -> walletService.createWallet(new CreateWalletRequest(user("u_other"), samePhone)));
            assertEquals("A wallet already exists for phone: 01711000001", ex.getMessage());
        }
        assertEquals(1, walletCount());
    }

    @Test
    void sameUserAndSamePhoneReportsDuplicateWallet() {
        walletService.createWallet(new CreateWalletRequest(user("u_1001"), "01711000001"));

        assertThrows(DuplicateWalletException.class,
                () -> walletService.createWallet(new CreateWalletRequest(user("u_1001"), "+8801711000001")));
    }

    @Test
    void rejectsInvalidPhoneWithoutWritingAnything() {
        assertThrows(InvalidPhoneNumberException.class,
                () -> walletService.createWallet(new CreateWalletRequest(user("u_1001"), "12345")));
        assertEquals(0, walletCount());
    }

    @Test
    void getsExistingWallet() {
        WalletResponse created = walletService.createWallet(new CreateWalletRequest(user("u_1001"), "1711000001"));

        assertEquals(created, walletService.getWallet(created.walletId()));
    }

    @Test
    void walletBalanceIsTheLatestLedgerBalance() {
        WalletResponse wallet = walletService.createWallet(new CreateWalletRequest(user("u_1001"), "01711000001"));
        UUID id = wallet.walletId();
        jdbcClient.sql("""
                        INSERT INTO wallet_transactions
                            (id, wallet_id, entry_no, type, amount, balance_before, balance_after, reference_id,
                             payment_method)
                        VALUES (:t1, :w, 1, 'ADD_MONEY', 50000, 0, 50000, gen_random_uuid(), 'VISA'),
                               (:t2, :w, 2, 'ADD_MONEY', 20000, 50000, 70000, gen_random_uuid(), 'MASTERCARD')
                        """)
                .param("t1", UUID.randomUUID()).param("t2", UUID.randomUUID()).param("w", id)
                .update();

        assertEquals(0, wallet.balance());
        assertEquals(70000, walletService.getWallet(id).balance());
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
                    return walletService.createWallet(new CreateWalletRequest(user("u_race"), "01911000009"));
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
                UUID userId = user("u_phone_race_" + i);
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

    private String storedPhone(UUID walletId) {
        return jdbcClient.sql("SELECT phone FROM wallets WHERE id = :id")
                .param("id", walletId)
                .query(String.class)
                .single();
    }

    private long walletCount() {
        return jdbcClient.sql("SELECT count(*) FROM wallets").query(Long.class).single();
    }

    /** Deterministic user_id (a UUID) for a readable test name. */
    private static UUID user(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
