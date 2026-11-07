package com.pathaopay.miniwallet.repository.jdbc;

import com.pathaopay.miniwallet.common.constant.DbConstants.LedgerEntries;
import com.pathaopay.miniwallet.common.constant.DbConstants.Wallets;
import com.pathaopay.miniwallet.model.enums.Currency;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.model.WalletBalance;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcWalletRepository implements WalletRepository {

    private static final String WALLET_COLUMNS = String.join(", ",
            Wallets.WALLET_ID, Wallets.USER_ID, Wallets.PHONE, Wallets.CURRENCY, Wallets.TYPE, Wallets.CREATED_AT);

    // ON CONFLICT (the unique phone) makes the one-wallet-per-phone rule race-safe: concurrent duplicates insert nothing.
    private static final String INSERT_IF_ABSENT =
            "INSERT INTO " + Wallets.TABLE
                    + " (" + String.join(", ", Wallets.WALLET_ID, Wallets.USER_ID, Wallets.PHONE, Wallets.CURRENCY,
                    Wallets.TYPE) + ")"
                    + " VALUES (:id, :userId, :phone, :currency, :type)"
                    + " ON CONFLICT DO NOTHING"
                    + " RETURNING " + WALLET_COLUMNS;

    private static final String FIND_BY_ID =
            "SELECT " + WALLET_COLUMNS + " FROM " + Wallets.TABLE + " WHERE " + Wallets.WALLET_ID + " = :id";

    private static final String FIND_BY_PHONE =
            "SELECT " + WALLET_COLUMNS + " FROM " + Wallets.TABLE + " WHERE " + Wallets.PHONE + " = :phone";

    // The ledger is the source of truth: balance = balance_after of the latest entry.
    private static final String FIND_LATEST_BALANCE =
            "SELECT " + LedgerEntries.BALANCE_AFTER + ", " + LedgerEntries.CREATED_AT
                    + " FROM " + LedgerEntries.TABLE
                    + " WHERE " + LedgerEntries.WALLET_ID + " = :id"
                    + " ORDER BY " + LedgerEntries.ENTRY_NO + " DESC"
                    + " LIMIT 1";

    private static final String LOCK_FOR_UPDATE =
            "SELECT " + Wallets.TYPE + " FROM " + Wallets.TABLE + " WHERE " + Wallets.WALLET_ID + " = :id FOR UPDATE";

    private static final String EXISTS_BY_PHONE =
            "SELECT EXISTS (SELECT 1 FROM " + Wallets.TABLE + " WHERE " + Wallets.PHONE + " = :phone)";

    private static final RowMapper<Wallet> WALLET_ROW_MAPPER = (rs, rowNum) -> new Wallet(
            rs.getObject(Wallets.WALLET_ID, UUID.class),
            rs.getObject(Wallets.USER_ID, UUID.class),
            rs.getString(Wallets.PHONE),
            Currency.valueOf(rs.getString(Wallets.CURRENCY)),
            WalletType.valueOf(rs.getString(Wallets.TYPE)),
            rs.getObject(Wallets.CREATED_AT, OffsetDateTime.class).toInstant());

    private final JdbcClient jdbcClient;

    @Override
    public Optional<Wallet> insertIfAbsent(UUID walletId, UUID userId, String phone, Currency currency, WalletType type) {
        return jdbcClient.sql(INSERT_IF_ABSENT)
                .param("id", walletId)
                .param("userId", userId)
                .param("phone", phone)
                .param("currency", currency.name())
                .param("type", type.name())
                .query(WALLET_ROW_MAPPER)
                .optional();
    }

    @Override
    public boolean existsByPhone(String phone) {
        return jdbcClient.sql(EXISTS_BY_PHONE).param("phone", phone).query(Boolean.class).single();
    }

    @Override
    public Optional<WalletType> lockForUpdate(UUID walletId) {
        return jdbcClient.sql(LOCK_FOR_UPDATE).param("id", walletId).query(String.class).optional()
                .map(WalletType::valueOf);
    }

    @Override
    public Optional<Wallet> findByPhone(String phone) {
        return jdbcClient.sql(FIND_BY_PHONE).param("phone", phone).query(WALLET_ROW_MAPPER).optional();
    }

    @Override
    public Optional<WalletBalance> findLatestBalance(UUID walletId) {
        return jdbcClient.sql(FIND_LATEST_BALANCE).param("id", walletId)
                .query((rs, rowNum) -> new WalletBalance(rs.getLong(LedgerEntries.BALANCE_AFTER),
                        rs.getObject(LedgerEntries.CREATED_AT, OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public Optional<Wallet> findById(UUID walletId) {
        return jdbcClient.sql(FIND_BY_ID)
                .param("id", walletId)
                .query(WALLET_ROW_MAPPER)
                .optional();
    }
}
