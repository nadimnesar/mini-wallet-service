package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.Currency;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.model.WalletBalance;
import com.pathaopay.miniwallet.model.WalletType;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {

    /**
     * Inserts a new customer (LIABILITY) wallet unless {@code userId} or {@code phone} is already used by
     * another wallet. Safe under concurrency: at most one of several racing inserts succeeds.
     *
     * @return the created wallet, or empty if the user or the phone already has a wallet
     */
    Optional<Wallet> insertIfAbsent(UUID walletId, UUID userId, String phone, Currency currency);

    boolean existsByUserId(UUID userId);

    boolean existsByPhone(String phone);

    Optional<Wallet> findById(UUID walletId);

    /** Looks a wallet up by its canonical phone number (01XXXXXXXXX), the external identity. */
    Optional<Wallet> findByPhone(String phone);

    boolean existsById(UUID walletId);

    /** The wallet's balance from its newest ledger entry, or empty if it has no entries (balance 0). */
    Optional<WalletBalance> findLatestBalance(UUID walletId);

    /**
     * Locks the wallet row ({@code SELECT ... FOR UPDATE}) until the current transaction ends, which
     * serializes ledger appends for that wallet.
     *
     * @return the wallet's account type, or empty if the wallet does not exist
     */
    Optional<WalletType> lockForUpdate(UUID walletId);
}
