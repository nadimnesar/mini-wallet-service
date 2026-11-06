package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.TransactionFilter;
import com.pathaopay.miniwallet.model.WalletTransaction;

import java.util.List;
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

    /**
     * Appends a TRANSFER_OUT or TRANSFER_IN entry. The caller must hold the wallet's row lock; a
     * transfer appends both legs in the same transaction, with the transfer id as reference_id.
     */
    WalletTransaction appendTransferLeg(WalletTransaction entry);

    /** The sender's leg of a transfer, which carries everything needed to rebuild its response. */
    Optional<WalletTransaction> findTransferOut(UUID transferId);

    Optional<WalletTransaction> findById(UUID transactionId);

    /** Entries matching the filter, newest first. */
    List<WalletTransaction> search(TransactionFilter filter, int limit, long offset);

    long count(TransactionFilter filter);
}
