package com.pathaopay.miniwallet.repository.jdbc;

import com.pathaopay.miniwallet.model.Currency;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.repository.WalletRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWalletRepository implements WalletRepository {

    private static final String WALLET_COLUMNS = "id, user_id, phone, currency, created_at, updated_at";

    // ON CONFLICT (any unique constraint: user_id or phone) makes the one-wallet-per-user and
    // one-wallet-per-phone rules race-safe: concurrent duplicates insert nothing.
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO wallets (id, user_id, phone, currency)
            VALUES (:id, :userId, :phone, :currency)
            ON CONFLICT DO NOTHING
            RETURNING %s, 0::BIGINT AS balance
            """.formatted(WALLET_COLUMNS);

    // The ledger is the source of truth: balance = balance_after of the latest entry, 0 if none.
    private static final String FIND_BY_ID = """
            SELECT w.id, w.user_id, w.phone, w.currency, w.created_at, w.updated_at,
                   COALESCE(latest.balance_after, 0) AS balance
            FROM wallets w
            LEFT JOIN LATERAL (
                SELECT t.balance_after
                FROM wallet_transactions t
                WHERE t.wallet_id = w.id
                ORDER BY t.entry_no DESC
                LIMIT 1
            ) latest ON TRUE
            WHERE w.id = :id
            """;
    private static final String EXISTS_BY_ID = "SELECT EXISTS (SELECT 1 FROM wallets WHERE id = :id)";
    private static final String LOCK_FOR_UPDATE = "SELECT id FROM wallets WHERE id = :id FOR UPDATE";
    private static final String EXISTS_BY_USER_ID = "SELECT EXISTS (SELECT 1 FROM wallets WHERE user_id = :userId)";
    private static final String EXISTS_BY_PHONE = "SELECT EXISTS (SELECT 1 FROM wallets WHERE phone = :phone)";

    private static final RowMapper<Wallet> WALLET_ROW_MAPPER = (rs, rowNum) -> new Wallet(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("phone"),
            Currency.valueOf(rs.getString("currency")),
            rs.getLong("balance"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbcClient;

    public JdbcWalletRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Wallet> insertIfAbsent(UUID walletId, UUID userId, String phone, Currency currency) {
        return jdbcClient.sql(INSERT_IF_ABSENT)
                .param("id", walletId)
                .param("userId", userId)
                .param("phone", phone)
                .param("currency", currency.name())
                .query(WALLET_ROW_MAPPER)
                .optional();
    }

    @Override
    public boolean existsByUserId(UUID userId) {
        return jdbcClient.sql(EXISTS_BY_USER_ID).param("userId", userId).query(Boolean.class).single();
    }

    @Override
    public boolean existsByPhone(String phone) {
        return jdbcClient.sql(EXISTS_BY_PHONE).param("phone", phone).query(Boolean.class).single();
    }

    @Override
    public boolean existsById(UUID walletId) {
        return jdbcClient.sql(EXISTS_BY_ID).param("id", walletId).query(Boolean.class).single();
    }

    @Override
    public boolean lockForUpdate(UUID walletId) {
        return jdbcClient.sql(LOCK_FOR_UPDATE).param("id", walletId).query(UUID.class).optional().isPresent();
    }

    @Override
    public Optional<Wallet> findById(UUID walletId) {
        return jdbcClient.sql(FIND_BY_ID)
                .param("id", walletId)
                .query(WALLET_ROW_MAPPER)
                .optional();
    }
}
