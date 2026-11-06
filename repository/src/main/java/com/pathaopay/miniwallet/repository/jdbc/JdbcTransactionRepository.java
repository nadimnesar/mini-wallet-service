package com.pathaopay.miniwallet.repository.jdbc;

import com.pathaopay.miniwallet.common.constant.DbConstants.WalletTransactions;
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

    private static final String ADD_MONEY = "'" + TransactionType.ADD_MONEY.name() + "'";

    private static final String COLUMNS = String.join(", ",
            WalletTransactions.ID, WalletTransactions.WALLET_ID, WalletTransactions.ENTRY_NO, WalletTransactions.TYPE,
            WalletTransactions.AMOUNT, WalletTransactions.BALANCE_BEFORE, WalletTransactions.BALANCE_AFTER,
            WalletTransactions.COUNTERPARTY_WALLET_ID, WalletTransactions.REFERENCE_ID,
            WalletTransactions.PAYMENT_METHOD, WalletTransactions.CREATED_AT);

    private static final String FIND_LATEST =
            "SELECT " + COLUMNS
                    + " FROM " + WalletTransactions.TABLE
                    + " WHERE " + WalletTransactions.WALLET_ID + " = :walletId"
                    + " ORDER BY " + WalletTransactions.ENTRY_NO + " DESC"
                    + " LIMIT 1";

    private static final String EXISTS_ADD_MONEY_WITH_GATEWAY_REF =
            "SELECT EXISTS (SELECT 1 FROM " + WalletTransactions.TABLE
                    + " WHERE " + WalletTransactions.TYPE + " = " + ADD_MONEY
                    + " AND " + WalletTransactions.REFERENCE_ID + " = :ref)";

    // The conflict target is the partial unique index on gateway_ref, so a concurrent request that
    // already credited the same gateway_ref makes this insert a no-op instead of aborting the transaction.
    private static final String APPEND_ADD_MONEY =
            "INSERT INTO " + WalletTransactions.TABLE + " (" + String.join(", ",
                    WalletTransactions.ID, WalletTransactions.WALLET_ID, WalletTransactions.ENTRY_NO,
                    WalletTransactions.TYPE, WalletTransactions.AMOUNT, WalletTransactions.BALANCE_BEFORE,
                    WalletTransactions.BALANCE_AFTER, WalletTransactions.REFERENCE_ID,
                    WalletTransactions.PAYMENT_METHOD) + ")"
                    + " VALUES (:id, :walletId, :entryNo, " + ADD_MONEY + ", :amount, :balanceBefore, :balanceAfter,"
                    + " :referenceId, :paymentMethod)"
                    + " ON CONFLICT (" + WalletTransactions.REFERENCE_ID + ")"
                    + " WHERE " + WalletTransactions.TYPE + " = " + ADD_MONEY + " DO NOTHING"
                    + " RETURNING " + COLUMNS;

    private static final String FIND_BY_ID =
            "SELECT " + COLUMNS + " FROM " + WalletTransactions.TABLE + " WHERE " + WalletTransactions.ID + " = :id";

    private static final RowMapper<WalletTransaction> ROW_MAPPER = (rs, rowNum) -> new WalletTransaction(
            rs.getObject(WalletTransactions.ID, UUID.class),
            rs.getObject(WalletTransactions.WALLET_ID, UUID.class),
            rs.getLong(WalletTransactions.ENTRY_NO),
            TransactionType.valueOf(rs.getString(WalletTransactions.TYPE)),
            rs.getLong(WalletTransactions.AMOUNT),
            rs.getLong(WalletTransactions.BALANCE_BEFORE),
            rs.getLong(WalletTransactions.BALANCE_AFTER),
            rs.getObject(WalletTransactions.COUNTERPARTY_WALLET_ID, UUID.class),
            rs.getObject(WalletTransactions.REFERENCE_ID, UUID.class),
            rs.getString(WalletTransactions.PAYMENT_METHOD) == null
                    ? null : PaymentMethod.valueOf(rs.getString(WalletTransactions.PAYMENT_METHOD)),
            rs.getObject(WalletTransactions.CREATED_AT, OffsetDateTime.class).toInstant());

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
