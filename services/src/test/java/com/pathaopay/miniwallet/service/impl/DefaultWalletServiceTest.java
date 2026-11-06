package com.pathaopay.miniwallet.service.impl;

import com.pathaopay.miniwallet.common.DuplicateWalletException;
import com.pathaopay.miniwallet.common.InvalidPhoneNumberException;
import com.pathaopay.miniwallet.common.WalletNotFoundException;
import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.WalletResponse;
import com.pathaopay.miniwallet.model.Currency;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultWalletServiceTest {

    private final InMemoryWalletRepository repository = new InMemoryWalletRepository();
    private final DefaultWalletService service = new DefaultWalletService(repository);

    @Test
    void createsZeroBalanceBdtWalletWithNormalizedPhone() {
        WalletResponse response = service.createWallet(new CreateWalletRequest("u_1001", "+8801711000001"));

        assertTrue(response.walletId().matches("^w_[0-9a-f]{32}$"));
        assertEquals("u_1001", response.userId());
        assertEquals(Currency.BDT, response.currency());
        assertEquals(0, response.balance());
        assertEquals("01711000001", repository.byId.get(response.walletId()).phone());
    }

    @Test
    void rejectsSecondWalletForSameUser() {
        service.createWallet(new CreateWalletRequest("u_1001", "01711000001"));

        assertThrows(DuplicateWalletException.class,
                () -> service.createWallet(new CreateWalletRequest("u_1001", "01811000002")));
    }

    @Test
    void rejectsInvalidPhoneBeforeTouchingRepository() {
        assertThrows(InvalidPhoneNumberException.class,
                () -> service.createWallet(new CreateWalletRequest("u_1001", "12345")));
        assertTrue(repository.byId.isEmpty());
    }

    @Test
    void getsExistingWallet() {
        WalletResponse created = service.createWallet(new CreateWalletRequest("u_1001", "1711000001"));

        assertEquals(created, service.getWallet(created.walletId()));
    }

    @Test
    void throwsWhenWalletMissing() {
        assertThrows(WalletNotFoundException.class, () -> service.getWallet("w_missing"));
    }

    private static final class InMemoryWalletRepository implements WalletRepository {
        final Map<String, Wallet> byId = new HashMap<>();

        @Override
        public Optional<Wallet> insertIfUserHasNone(String walletId, String userId, String phone, Currency currency) {
            if (byId.values().stream().anyMatch(w -> w.userId().equals(userId))) {
                return Optional.empty();
            }
            Instant now = Instant.now();
            Wallet wallet = new Wallet(walletId, userId, phone, currency, 0, now, now);
            byId.put(walletId, wallet);
            return Optional.of(wallet);
        }

        @Override
        public Optional<Wallet> findById(String walletId) {
            return Optional.ofNullable(byId.get(walletId));
        }
    }
}
