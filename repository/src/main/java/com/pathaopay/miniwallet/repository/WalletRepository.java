package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.Currency;
import com.pathaopay.miniwallet.model.Wallet;

import java.util.Optional;

public interface WalletRepository {

    /**
     * Inserts a new zero-balance wallet unless {@code userId} already owns one.
     *
     * @return the created wallet, or empty if the user already has a wallet
     */
    Optional<Wallet> insertIfUserHasNone(String walletId, String userId, String phone, Currency currency);

    Optional<Wallet> findById(String walletId);
}
