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

    private static final String COLUMNS = "id, user_id, phone, currency, balance, created_at, updated_at";

    // ON CONFLICT (any unique constraint: user_id or phone) makes the one-wallet-per-user and
    // one-wallet-per-phone rules race-safe: concurrent duplicates insert nothing.
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO wallets (id, user_id, phone, currency)
            VALUES (:id, :userId, :phone, :currency)
            ON CONFLICT DO NOTHING
            RETURNING %s
            """.formatted(COLUMNS);

    private static final String FIND_BY_ID = "SELECT %s FROM wallets WHERE id = :id".formatted(COLUMNS);
    private static final String EXISTS_BY_USER_ID = "SELECT EXISTS (SELECT 1 FROM wallets WHERE user_id = :userId)";
    private static final String EXISTS_BY_PHONE = "SELECT EXISTS (SELECT 1 FROM wallets WHERE phone = :phone)";

    private static final RowMapper<Wallet> WALLET_ROW_MAPPER = (rs, rowNum) -> new Wallet(
            rs.getObject("id", UUID.class),
            rs.getString("user_id"),
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
    public Optional<Wallet> insertIfAbsent(UUID walletId, String userId, String phone, Currency currency) {
        return jdbcClient.sql(INSERT_IF_ABSENT)
                .param("id", walletId)
                .param("userId", userId)
                .param("phone", phone)
                .param("currency", currency.name())
                .query(WALLET_ROW_MAPPER)
                .optional();
    }

    @Override
    public boolean existsByUserId(String userId) {
        return jdbcClient.sql(EXISTS_BY_USER_ID).param("userId", userId).query(Boolean.class).single();
    }

    @Override
    public boolean existsByPhone(String phone) {
        return jdbcClient.sql(EXISTS_BY_PHONE).param("phone", phone).query(Boolean.class).single();
    }

    @Override
    public Optional<Wallet> findById(UUID walletId) {
        return jdbcClient.sql(FIND_BY_ID)
                .param("id", walletId)
                .query(WALLET_ROW_MAPPER)
                .optional();
    }
}
