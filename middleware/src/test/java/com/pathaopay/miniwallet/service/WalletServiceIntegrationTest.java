package com.pathaopay.miniwallet.service;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.DbConstants.LedgerEntries;
import com.pathaopay.miniwallet.common.constant.DbConstants.Wallets;
import com.pathaopay.miniwallet.common.exception.wallet.DuplicatePhoneException;
import com.pathaopay.miniwallet.common.exception.wallet.DuplicateWalletException;
import com.pathaopay.miniwallet.common.exception.wallet.InvalidPhoneNumberException;
import com.pathaopay.miniwallet.common.exception.wallet.ReservedUserIdException;
import com.pathaopay.miniwallet.common.exception.wallet.WalletNotFoundException;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.response.WalletResponse;
import com.pathaopay.miniwallet.model.enums.Currency;
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
        assertEquals(0, walletService.getBalance(response.phone()).balance());
        assertEquals("01711000001", storedPhone(user("u_1001")));
        assertEquals(AppConstants.SYSTEM_USER_ID.equals(response.userId()), false);
    }

    @Test
    void rejectsPhonesOfSystemAccounts() {
        assertThrows(InvalidPhoneNumberException.class,
                () -> walletService.createWallet(new CreateWalletRequest(user("u_1001"), AppConstants.REVENUE_WALLET_PHONE)));
        assertEquals(0, walletCount());
    }

    @Test
    void rejectsUnassignedOperatorPrefixes() {
        for (String phone : List.of("01011000001", "01111000001", "01211000001", "1011000001", "+8801011000001")) {
            assertThrows(InvalidPhoneNumberException.class,
                    () -> walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), phone)), phone);
        }
        assertEquals(0, walletCount());
        walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01311000001"));
        walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01911000001"));
        assertEquals(2, walletCount());
    }

    @Test
    void rejectsTheUserIdReservedForSystemAccounts() {
        assertThrows(ReservedUserIdException.class,
                () -> walletService.createWallet(new CreateWalletRequest(AppConstants.SYSTEM_USER_ID, "01711000001")));
        assertEquals(0, walletCount());
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

        assertEquals(created, walletService.getWallet(created.phone()));
        assertEquals(created, walletService.getWallet("+880" + created.phone().substring(1)));
    }

    @Test
    void walletBalanceIsTheLatestLedgerBalance() {
        WalletResponse wallet = walletService.createWallet(new CreateWalletRequest(user("u_1001"), "01711000001"));
        assertEquals(0, walletService.getBalance(wallet.phone()).balance());
        UUID id = jdbcClient.sql("SELECT wallet_id FROM wallets WHERE phone = :p").param("p", wallet.phone())
                .query(UUID.class).single();
        jdbcClient.sql("""
                        WITH t1 AS (INSERT INTO transactions (id, type, amount, gateway_ref, payment_method)
                                    VALUES (:r1, 'ADD_MONEY', 50000, gen_random_uuid(), 'VISA')),
                             t2 AS (INSERT INTO transactions (id, type, amount, gateway_ref, payment_method)
                                    VALUES (:r2, 'ADD_MONEY', 20000, gen_random_uuid(), 'MASTERCARD'))
                        INSERT INTO ledger_entries (id, wallet_id, entry_no, wallet_type, type, direction, amount,
                                                    balance_before, balance_after, counterparty_wallet_id, transaction_id)
                        VALUES (:t1, :w, 1, 'LIABILITY', 'ADD_MONEY', 'CREDIT', 50000, 0, 50000, :c, :r1),
                               (:t2, :c, 1, 'ASSET', 'GATEWAY_CLEARING', 'DEBIT', 50000, 0, 50000, :w, :r1),
                               (:t3, :w, 2, 'LIABILITY', 'ADD_MONEY', 'CREDIT', 20000, 50000, 70000, :c, :r2),
                               (:t4, :c, 2, 'ASSET', 'GATEWAY_CLEARING', 'DEBIT', 20000, 50000, 70000, :w, :r2)
                        """)
                .param("t1", UUID.randomUUID()).param("t2", UUID.randomUUID())
                .param("t3", UUID.randomUUID()).param("t4", UUID.randomUUID())
                .param("r1", UUID.randomUUID()).param("r2", UUID.randomUUID())
                .param("c", AppConstants.GATEWAY_CLEARING_WALLET_ID).param("w", id)
                .update();

        assertEquals(70000, walletService.getBalance(wallet.phone()).balance());
    }

    @Test
    void throwsWhenWalletMissing() {
        assertThrows(WalletNotFoundException.class, () -> walletService.getWallet("01999999999"));
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

    private String storedPhone(UUID userId) {
        return jdbcClient.sql("SELECT " + Wallets.PHONE + " FROM " + Wallets.TABLE + " WHERE " + Wallets.USER_ID + " = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    /** Counts customer wallets; the seeded system accounts are excluded. */
    private long walletCount() {
        return jdbcClient.sql("SELECT count(*) FROM " + Wallets.TABLE + " WHERE " + Wallets.TYPE + " = 'LIABILITY'")
                .query(Long.class).single();
    }

    /** Deterministic user_id (a UUID) for a readable test name. */
    private static UUID user(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
