package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.enums.EntryType;
import com.pathaopay.miniwallet.model.LedgerEntry;
import com.pathaopay.miniwallet.model.LedgerEntryView;
import com.pathaopay.miniwallet.model.LedgerTransaction;
import com.pathaopay.miniwallet.model.TransactionFilter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only access to {@code transactions} (one row per business operation) and {@code ledger_entries} (its
 * debit/credit legs). A transaction and all of its entries must be written in one database transaction: the database
 * rejects, at commit, a transaction whose entries do not balance.
 */
public interface TransactionRepository {

    /** The wallet's newest entry (highest entry_no), or empty for a wallet without entries. */
    Optional<LedgerEntry> findLatestEntry(UUID walletId);

    boolean existsByGatewayRef(UUID gatewayRef);

    /**
     * Inserts an ADD_MONEY transaction.
     *
     * @return the stored transaction, or empty if its gateway_ref was already used
     */
    Optional<LedgerTransaction> insertAddMoney(LedgerTransaction transaction);

    LedgerTransaction insert(LedgerTransaction transaction);

    /** Appends one leg. The caller must hold the wallet's row lock and have inserted the transaction. */
    LedgerEntry appendEntry(LedgerEntry entry);

    Optional<LedgerTransaction> findTransaction(UUID transactionId);

    /** All entries of a transaction, in the order they were written, with their wallets' phone numbers. */
    List<LedgerEntryView> findEntries(UUID transactionId);

    /** The transaction's entry of the given type (every transaction has at most one of each type per wallet). */
    Optional<LedgerEntry> findEntry(UUID transactionId, EntryType type);

    /** Entries matching the filter, newest first, with their wallets' phone numbers. */
    List<LedgerEntryView> search(TransactionFilter filter, int limit, long offset);

    long count(TransactionFilter filter);
}
