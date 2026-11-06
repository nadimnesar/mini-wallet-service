package com.pathaopay.miniwallet.repository.jdbc;

import com.pathaopay.miniwallet.common.constant.DbConstants.WalletTransactions;
import com.pathaopay.miniwallet.model.PaymentMethod;
import com.pathaopay.miniwallet.model.TransactionFilter;
import com.pathaopay.miniwallet.model.TransactionType;
import com.pathaopay.miniwallet.model.WalletTransaction;
import com.pathaopay.miniwallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final String APPEND_TRANSFER_LEG =
            "INSERT INTO " + WalletTransactions.TABLE + " (" + String.join(", ",
                    WalletTransactions.ID, WalletTransactions.WALLET_ID, WalletTransactions.ENTRY_NO,
                    WalletTransactions.TYPE, WalletTransactions.AMOUNT, WalletTransactions.BALANCE_BEFORE,
                    WalletTransactions.BALANCE_AFTER, WalletTransactions.COUNTERPARTY_WALLET_ID,
                    WalletTransactions.REFERENCE_ID) + ")"
                    + " VALUES (:id, :walletId, :entryNo, :type, :amount, :balanceBefore, :balanceAfter,"
                    + " :counterpartyWalletId, :referenceId)"
                    + " RETURNING " + COLUMNS;

    private static final String FIND_TRANSFER_OUT =
            "SELECT " + COLUMNS + " FROM " + WalletTransactions.TABLE
                    + " WHERE " + WalletTransactions.REFERENCE_ID + " = :transferId"
                    + " AND " + WalletTransactions.TYPE + " = '" + TransactionType.TRANSFER_OUT.name() + "'";

    private static final String NEWEST_FIRST =
            " ORDER BY " + WalletTransactions.CREATED_AT + " DESC, " + WalletTransactions.ID + " DESC";

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

    @Override
    public WalletTransaction appendTransferLeg(WalletTransaction entry) {
        return jdbcClient.sql(APPEND_TRANSFER_LEG)
                .param("id", entry.id())
                .param("walletId", entry.walletId())
                .param("entryNo", entry.entryNo())
                .param("type", entry.type().name())
                .param("amount", entry.amount())
                .param("balanceBefore", entry.balanceBefore())
                .param("balanceAfter", entry.balanceAfter())
                .param("counterpartyWalletId", entry.counterpartyWalletId())
                .param("referenceId", entry.referenceId())
                .query(ROW_MAPPER)
                .single();
    }

    @Override
    public Optional<WalletTransaction> findTransferOut(UUID transferId) {
        return jdbcClient.sql(FIND_TRANSFER_OUT).param("transferId", transferId).query(ROW_MAPPER).optional();
    }

    @Override
    public List<WalletTransaction> search(TransactionFilter filter, int limit, long offset) {
        Where where = Where.of(filter);
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM " + WalletTransactions.TABLE + where.sql
                        + NEWEST_FIRST + " LIMIT :limit OFFSET :offset")
                .params(where.params)
                .param("limit", limit)
                .param("offset", offset)
                .query(ROW_MAPPER)
                .list();
    }

    @Override
    public long count(TransactionFilter filter) {
        Where where = Where.of(filter);
        return jdbcClient.sql("SELECT count(*) FROM " + WalletTransactions.TABLE + where.sql)
                .params(where.params)
                .query(Long.class)
                .single();
    }

    /** WHERE clause with only the filter fields that are set, so no untyped NULL parameters are sent. */
    private record Where(String sql, Map<String, Object> params) {
        static Where of(TransactionFilter filter) {
            List<String> conditions = new ArrayList<>();
            Map<String, Object> params = new HashMap<>();
            if (filter.walletId() != null) {
                conditions.add(WalletTransactions.WALLET_ID + " = :walletId");
                params.put("walletId", filter.walletId());
            }
            if (filter.type() != null) {
                conditions.add(WalletTransactions.TYPE + " = :type");
                params.put("type", filter.type().name());
            }
            if (filter.from() != null) {
                conditions.add(WalletTransactions.CREATED_AT + " >= :from");
                params.put("from", OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
            }
            if (filter.to() != null) {
                conditions.add(WalletTransactions.CREATED_AT + " <= :to");
                params.put("to", OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
            }
            return new Where(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
        }
    }
}
