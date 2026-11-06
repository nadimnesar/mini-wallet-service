package com.pathaopay.miniwallet.client.repository;

import com.pathaopay.miniwallet.model.Currency;
import com.pathaopay.miniwallet.model.Wallet;
import com.pathaopay.miniwallet.repository.WalletRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class JdbcWalletRepository implements WalletRepository {

    private static final String COLUMNS = "id, user_id, phone, currency, balance, created_at, updated_at";

    // ON CONFLICT makes the one-wallet-per-user rule race-safe: concurrent duplicates insert nothing.
    private static final String INSERT_IF_USER_HAS_NONE = """
            INSERT INTO wallets (id, user_id, phone, currency)
            VALUES (:id, :userId, :phone, :currency)
            ON CONFLICT (user_id) DO NOTHING
            RETURNING %s
            """.formatted(COLUMNS);

    private static final String FIND_BY_ID = "SELECT %s FROM wallets WHERE id = :id".formatted(COLUMNS);

    private static final RowMapper<Wallet> WALLET_ROW_MAPPER = (rs, rowNum) -> new Wallet(
            rs.getString("id"),
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
    public Optional<Wallet> insertIfUserHasNone(String walletId, String userId, String phone, Currency currency) {
        return jdbcClient.sql(INSERT_IF_USER_HAS_NONE)
                .param("id", walletId)
                .param("userId", userId)
                .param("phone", phone)
                .param("currency", currency.name())
                .query(WALLET_ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<Wallet> findById(String walletId) {
        return jdbcClient.sql(FIND_BY_ID)
                .param("id", walletId)
                .query(WALLET_ROW_MAPPER)
                .optional();
    }
}
