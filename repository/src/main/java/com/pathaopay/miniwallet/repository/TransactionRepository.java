package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.WalletTransaction;

import java.util.Optional;
import java.util.UUID;

/** Append-only access to the ledger ({@code wallet_transactions}). */
public interface TransactionRepository {

    /** The wallet's newest entry (highest entry_no), or empty for a wallet without entries. */
    Optional<WalletTransaction> findLatestEntry(UUID walletId);

    boolean existsAddMoneyWithGatewayRef(UUID gatewayRef);

    /**
     * Appends an ADD_MONEY entry. The caller must hold the wallet's row lock.
     *
     * @return the stored entry, or empty if {@code entry.referenceId()} (gateway_ref) was already used
     */
    Optional<WalletTransaction> appendAddMoney(WalletTransaction entry);

    Optional<WalletTransaction> findById(UUID transactionId);
}
