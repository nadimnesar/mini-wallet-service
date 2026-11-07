package com.pathaopay.miniwallet.service.ledger;

import com.pathaopay.miniwallet.common.util.UuidGenerator;
import com.pathaopay.miniwallet.model.LedgerEntry;
import com.pathaopay.miniwallet.model.enums.EntryType;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.repository.TransactionRepository;
import com.pathaopay.miniwallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Locks wallets and appends ledger legs: the mechanics every money movement shares. */
@Component
@RequiredArgsConstructor
public class LedgerPoster {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Locks the wallets in ascending id order (the same for every operation, so two operations can never wait on
     * each other) and loads each one's latest ledger position. Must run inside the database transaction that writes the
     * legs.
     */
    public Map<UUID, Chain> lockAndLoadChains(Set<UUID> walletIds) {
        Map<UUID, Chain> chains = new HashMap<>();
        for (UUID id : new TreeSet<>(walletIds)) {
            WalletType type = walletRepository.lockForUpdate(id)
                    .orElseThrow(() -> new IllegalStateException("Wallet vanished: " + id));
            LedgerEntry latest = transactionRepository.findLatestEntry(id).orElse(null);
            chains.put(id, new Chain(transactionRepository, id, type, latest == null ? 0 : latest.entryNo(),
                    latest == null ? 0 : latest.balanceAfter()));
        }
        return chains;
    }

    /**
     * A wallet's ledger position within the running transaction: advanced as legs are appended, so a wallet can
     * receive several legs in one journal.
     */
    public static final class Chain {
        private final TransactionRepository repository;
        private final UUID walletId;
        private final WalletType type;
        private long entryNo;
        private long balance;

        private Chain(TransactionRepository repository, UUID walletId, WalletType type, long entryNo, long balance) {
            this.repository = repository;
            this.walletId = walletId;
            this.type = type;
            this.entryNo = entryNo;
            this.balance = balance;
        }

        public long balance() {
            return balance;
        }

        public LedgerEntry append(EntryType legType, long amount, UUID counterparty, UUID transactionId) {
            LedgerEntry stored = repository.appendEntry(new LedgerEntry(UuidGenerator.v7(), walletId, type,
                    entryNo + 1, legType, legType.direction(), amount, balance,
                    type.apply(balance, legType.direction(), amount), counterparty, transactionId, null));
            entryNo = stored.entryNo();
            balance = stored.balanceAfter();
            return stored;
        }
    }
}
