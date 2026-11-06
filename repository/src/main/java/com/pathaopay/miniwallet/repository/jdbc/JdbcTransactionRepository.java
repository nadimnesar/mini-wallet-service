package com.pathaopay.miniwallet.repository.jdbc;

import com.pathaopay.miniwallet.model.PaymentMethod;
import com.pathaopay.miniwallet.model.TransactionType;
import com.pathaopay.miniwallet.model.WalletTransaction;
import com.pathaopay.miniwallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcTransactionRepository implements TransactionRepository {

    private static final String COLUMNS = """
            id, wallet_id, entry_no, type, amount, balance_before, balance_after,
            counterparty_wallet_id, reference_id, payment_method, created_at""";

    private static final String FIND_LATEST = """
            SELECT %s FROM wallet_transactions
            WHERE wallet_id = :walletId
            ORDER BY entry_no DESC
            LIMIT 1
            """.formatted(COLUMNS);

    private static final String EXISTS_ADD_MONEY_WITH_GATEWAY_REF = """
            SELECT EXISTS (SELECT 1 FROM wallet_transactions WHERE type = 'ADD_MONEY' AND reference_id = :ref)""";

    // The conflict target is the partial unique index on gateway_ref, so a concurrent request that
    // already credited the same gateway_ref makes this insert a no-op instead of aborting the transaction.
    private static final String APPEND_ADD_MONEY = """
            INSERT INTO wallet_transactions (id, wallet_id, entry_no, type, amount, balance_before, balance_after,
                                             reference_id, payment_method)
            VALUES (:id, :walletId, :entryNo, 'ADD_MONEY', :amount, :balanceBefore, :balanceAfter,
                    :referenceId, :paymentMethod)
            ON CONFLICT (reference_id) WHERE type = 'ADD_MONEY' DO NOTHING
            RETURNING %s
            """.formatted(COLUMNS);

    private static final String FIND_BY_ID = "SELECT %s FROM wallet_transactions WHERE id = :id".formatted(COLUMNS);

    private static final RowMapper<WalletTransaction> ROW_MAPPER = (rs, rowNum) -> new WalletTransaction(
            rs.getObject("id", UUID.class),
            rs.getObject("wallet_id", UUID.class),
            rs.getLong("entry_no"),
            TransactionType.valueOf(rs.getString("type")),
            rs.getLong("amount"),
            rs.getLong("balance_before"),
            rs.getLong("balance_after"),
            rs.getObject("counterparty_wallet_id", UUID.class),
            rs.getObject("reference_id", UUID.class),
            rs.getString("payment_method") == null ? null : PaymentMethod.valueOf(rs.getString("payment_method")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbcClient;

    @Override
    public Optional<WalletTransaction> findLatestEntry(UUID walletId) {
        return jdbcClient.sql(FIND_LATEST).param("walletId", walletId).query(ROW_MAPPER).optional();
    }

    @Override
    public boolean existsAddMoneyWithGatewayRef(UUID gatewayRef) {
        return jdbcClient.sql(EXISTS_ADD_MONEY_WITH_GATEWAY_REF).param("ref", gatewayRef).query(Boolean.class).single();
    }

    @Override
    public Optional<WalletTransaction> appendAddMoney(WalletTransaction entry) {
        return jdbcClient.sql(APPEND_ADD_MONEY)
                .param("id", entry.id())
                .param("walletId", entry.walletId())
                .param("entryNo", entry.entryNo())
                .param("amount", entry.amount())
                .param("balanceBefore", entry.balanceBefore())
                .param("balanceAfter", entry.balanceAfter())
                .param("referenceId", entry.referenceId())
                .param("paymentMethod", entry.paymentMethod().name(), Types.VARCHAR)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<WalletTransaction> findById(UUID transactionId) {
        return jdbcClient.sql(FIND_BY_ID).param("id", transactionId).query(ROW_MAPPER).optional();
    }
}
