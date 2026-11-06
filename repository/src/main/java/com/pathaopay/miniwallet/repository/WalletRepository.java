package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.Currency;
import com.pathaopay.miniwallet.model.Wallet;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {

    /**
     * Inserts a new zero-balance wallet unless {@code userId} or {@code phone} is already used by
     * another wallet. Safe under concurrency: at most one of several racing inserts succeeds.
     *
     * @return the created wallet, or empty if the user or the phone already has a wallet
     */
    Optional<Wallet> insertIfAbsent(UUID walletId, String userId, String phone, Currency currency);

    boolean existsByUserId(String userId);

    boolean existsByPhone(String phone);

    Optional<Wallet> findById(UUID walletId);
}
