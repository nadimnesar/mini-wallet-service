package com.pathaopay.miniwallet.repository.impl;

import com.pathaopay.miniwallet.common.constant.DbConstants.LedgerEntries;
import com.pathaopay.miniwallet.common.constant.DbConstants.Transactions;
import com.pathaopay.miniwallet.common.constant.DbConstants.Wallets;
import com.pathaopay.miniwallet.model.enums.EntryDirection;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;
import com.pathaopay.miniwallet.model.TransactionFilter;
import com.pathaopay.miniwallet.model.enums.EntryType;
import com.pathaopay.miniwallet.model.LedgerEntryView;
import com.pathaopay.miniwallet.model.LedgerTransaction;
import com.pathaopay.miniwallet.model.enums.TransactionType;
import com.pathaopay.miniwallet.model.LedgerEntry;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class JdbcTransactionRepository implements TransactionRepository {

    private static final String ENTRY_COLUMNS = String.join(", ",
            LedgerEntries.ID, LedgerEntries.WALLET_ID, LedgerEntries.WALLET_TYPE,
            LedgerEntries.ENTRY_NO, LedgerEntries.TYPE, LedgerEntries.DIRECTION,
            LedgerEntries.AMOUNT, LedgerEntries.BALANCE_BEFORE, LedgerEntries.BALANCE_AFTER,
            LedgerEntries.COUNTERPARTY_WALLET_ID, LedgerEntries.TRANSACTION_ID, LedgerEntries.CREATED_AT);

    private static final String INSERT_ENTRY =
            "INSERT INTO " + LedgerEntries.TABLE + " (" + String.join(", ",
                    LedgerEntries.ID, LedgerEntries.WALLET_ID, LedgerEntries.WALLET_TYPE,
                    LedgerEntries.ENTRY_NO, LedgerEntries.TYPE, LedgerEntries.DIRECTION,
                    LedgerEntries.AMOUNT, LedgerEntries.BALANCE_BEFORE, LedgerEntries.BALANCE_AFTER,
                    LedgerEntries.COUNTERPARTY_WALLET_ID, LedgerEntries.TRANSACTION_ID, LedgerEntries.CREATED_AT) + ")"
                    + " VALUES (:id, :walletId, :walletType, :entryNo, :type, :direction, :amount, :balanceBefore,"
                    + " :balanceAfter, :counterpartyWalletId, :transactionId, clock_timestamp())"
                    + " RETURNING " + ENTRY_COLUMNS;

    private static final String FIND_LATEST =
            "SELECT " + ENTRY_COLUMNS
                    + " FROM " + LedgerEntries.TABLE
                    + " WHERE " + LedgerEntries.WALLET_ID + " = :walletId"
                    + " ORDER BY " + LedgerEntries.ENTRY_NO + " DESC"
                    + " LIMIT 1";

    // Entries for the external API: joined to both wallets so every wallet is shown by its phone number.
    private static final String VIEW_SELECT =
            "SELECT " + Arrays.stream(ENTRY_COLUMNS.split(", ")).map(c -> "e." + c).collect(Collectors.joining(", "))
                    + ", w." + Wallets.PHONE + " AS wallet_phone, c." + Wallets.PHONE + " AS counterparty_phone"
                    + " FROM " + LedgerEntries.TABLE + " e"
                    + " JOIN " + Wallets.TABLE + " w ON w." + Wallets.WALLET_ID + " = e." + LedgerEntries.WALLET_ID
                    + " JOIN " + Wallets.TABLE + " c ON c." + Wallets.WALLET_ID + " = e."
                    + LedgerEntries.COUNTERPARTY_WALLET_ID;

    private static final String FIND_ENTRIES =
            VIEW_SELECT + " WHERE e." + LedgerEntries.TRANSACTION_ID + " = :transactionId"
                    + " ORDER BY e." + LedgerEntries.CREATED_AT + ", e." + LedgerEntries.ID;

    private static final String FIND_ENTRY =
            "SELECT " + ENTRY_COLUMNS + " FROM " + LedgerEntries.TABLE
                    + " WHERE " + LedgerEntries.TRANSACTION_ID + " = :transactionId"
                    + " AND " + LedgerEntries.TYPE + " = :type";

    // A wallet's ledger chain (entry_no) is the true order of its history.
    private static final String NEWEST_FIRST = " ORDER BY e." + LedgerEntries.ENTRY_NO + " DESC";

    private static final String TRANSACTION_COLUMNS = String.join(", ",
            Transactions.ID, Transactions.TYPE, Transactions.AMOUNT, Transactions.GATEWAY_REF,
            Transactions.PAYMENT_METHOD, Transactions.NOTE, Transactions.CREATED_AT);

    private static final String INSERT_TRANSACTION =
            "INSERT INTO " + Transactions.TABLE + " (" + String.join(", ",
                    Transactions.ID, Transactions.TYPE, Transactions.AMOUNT, Transactions.GATEWAY_REF,
                    Transactions.PAYMENT_METHOD, Transactions.NOTE, Transactions.CREATED_AT) + ")"
                    + " VALUES (:id, :type, :amount, :gatewayRef, :paymentMethod, :note, clock_timestamp())";

    // A concurrent request that already credited the same gateway_ref makes this insert a no-op instead of
    // aborting the database transaction.
    private static final String INSERT_ADD_MONEY = INSERT_TRANSACTION
            + " ON CONFLICT (" + Transactions.GATEWAY_REF + ") DO NOTHING RETURNING " + TRANSACTION_COLUMNS;

    private static final String INSERT_TRANSACTION_RETURNING = INSERT_TRANSACTION + " RETURNING " + TRANSACTION_COLUMNS;

    private static final String FIND_TRANSACTION =
            "SELECT " + TRANSACTION_COLUMNS + " FROM " + Transactions.TABLE + " WHERE " + Transactions.ID + " = :id";

    private static final String EXISTS_BY_GATEWAY_REF =
            "SELECT EXISTS (SELECT 1 FROM " + Transactions.TABLE
                    + " WHERE " + Transactions.GATEWAY_REF + " = :ref)";

    private static final RowMapper<LedgerEntry> ENTRY_ROW_MAPPER = (rs, rowNum) -> new LedgerEntry(
            rs.getObject(LedgerEntries.ID, UUID.class),
            rs.getObject(LedgerEntries.WALLET_ID, UUID.class),
            WalletType.valueOf(rs.getString(LedgerEntries.WALLET_TYPE)),
            rs.getLong(LedgerEntries.ENTRY_NO),
            EntryType.valueOf(rs.getString(LedgerEntries.TYPE)),
            EntryDirection.valueOf(rs.getString(LedgerEntries.DIRECTION)),
            rs.getLong(LedgerEntries.AMOUNT),
            rs.getLong(LedgerEntries.BALANCE_BEFORE),
            rs.getLong(LedgerEntries.BALANCE_AFTER),
            rs.getObject(LedgerEntries.COUNTERPARTY_WALLET_ID, UUID.class),
            rs.getObject(LedgerEntries.TRANSACTION_ID, UUID.class),
            rs.getObject(LedgerEntries.CREATED_AT, OffsetDateTime.class).toInstant());

    private static final RowMapper<LedgerEntryView> VIEW_ROW_MAPPER = (rs, rowNum) -> new LedgerEntryView(
            ENTRY_ROW_MAPPER.mapRow(rs, rowNum), rs.getString("wallet_phone"), rs.getString("counterparty_phone"));

    private static final RowMapper<LedgerTransaction> TRANSACTION_ROW_MAPPER = (rs, rowNum) -> new LedgerTransaction(
            rs.getObject(Transactions.ID, UUID.class),
            TransactionType.valueOf(rs.getString(Transactions.TYPE)),
            rs.getLong(Transactions.AMOUNT),
            rs.getObject(Transactions.GATEWAY_REF, UUID.class),
            rs.getString(Transactions.PAYMENT_METHOD) == null
                    ? null : PaymentMethod.valueOf(rs.getString(Transactions.PAYMENT_METHOD)),
            rs.getString(Transactions.NOTE),
            rs.getObject(Transactions.CREATED_AT, OffsetDateTime.class).toInstant());

    private final JdbcClient jdbcClient;

    @Override
    public Optional<LedgerEntry> findLatestEntry(UUID walletId) {
        return jdbcClient.sql(FIND_LATEST).param("walletId", walletId).query(ENTRY_ROW_MAPPER).optional();
    }

    @Override
    public boolean existsByGatewayRef(UUID gatewayRef) {
        return jdbcClient.sql(EXISTS_BY_GATEWAY_REF).param("ref", gatewayRef).query(Boolean.class).single();
    }

    @Override
    public Optional<LedgerTransaction> insertAddMoney(LedgerTransaction transaction) {
        return bind(jdbcClient.sql(INSERT_ADD_MONEY), transaction).query(TRANSACTION_ROW_MAPPER).optional();
    }

    @Override
    public LedgerTransaction insert(LedgerTransaction transaction) {
        return bind(jdbcClient.sql(INSERT_TRANSACTION_RETURNING), transaction).query(TRANSACTION_ROW_MAPPER).single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, LedgerTransaction transaction) {
        return statement
                .param("id", transaction.id())
                .param("type", transaction.type().name())
                .param("amount", transaction.amount())
                .param("gatewayRef", transaction.gatewayRef(), Types.OTHER)
                .param("paymentMethod", transaction.paymentMethod() == null ? null : transaction.paymentMethod().name(),
                        Types.VARCHAR)
                .param("note", transaction.note(), Types.VARCHAR);
    }

    @Override
    public LedgerEntry appendEntry(LedgerEntry entry) {
        return jdbcClient.sql(INSERT_ENTRY)
                .param("id", entry.id())
                .param("walletId", entry.walletId())
                .param("walletType", entry.walletType().name())
                .param("entryNo", entry.entryNo())
                .param("type", entry.type().name())
                .param("direction", entry.direction().name())
                .param("amount", entry.amount())
                .param("balanceBefore", entry.balanceBefore())
                .param("balanceAfter", entry.balanceAfter())
                .param("counterpartyWalletId", entry.counterpartyWalletId())
                .param("transactionId", entry.transactionId())
                .query(ENTRY_ROW_MAPPER)
                .single();
    }

    @Override
    public Optional<LedgerTransaction> findTransaction(UUID transactionId) {
        return jdbcClient.sql(FIND_TRANSACTION).param("id", transactionId).query(TRANSACTION_ROW_MAPPER).optional();
    }

    @Override
    public List<LedgerEntryView> findEntries(UUID transactionId) {
        return jdbcClient.sql(FIND_ENTRIES).param("transactionId", transactionId).query(VIEW_ROW_MAPPER).list();
    }

    @Override
    public Optional<LedgerEntry> findEntry(UUID transactionId, EntryType type) {
        return jdbcClient.sql(FIND_ENTRY).param("transactionId", transactionId).param("type", type.name())
                .query(ENTRY_ROW_MAPPER).optional();
    }

    @Override
    public List<LedgerEntryView> search(TransactionFilter filter, int limit, long offset) {
        Where where = Where.of(filter);
        return jdbcClient.sql(VIEW_SELECT + where.sql + NEWEST_FIRST + " LIMIT :limit OFFSET :offset")
                .params(where.params)
                .param("limit", limit)
                .param("offset", offset)
                .query(VIEW_ROW_MAPPER)
                .list();
    }

    @Override
    public long count(TransactionFilter filter) {
        Where where = Where.of(filter);
        return jdbcClient.sql("SELECT count(*) FROM " + LedgerEntries.TABLE + " e" + where.sql)
                .params(where.params)
                .query(Long.class)
                .single();
    }

    /** WHERE clause with only the filter fields that are set, so no untyped NULL parameters are sent. */
    private record Where(String sql, Map<String, Object> params) {
        static Where of(TransactionFilter filter) {
            List<String> conditions = new ArrayList<>();
            Map<String, Object> params = new HashMap<>();
            conditions.add("e." + LedgerEntries.WALLET_ID + " = :walletId");
            params.put("walletId", filter.walletId());
            if (filter.type() != null) {
                conditions.add("e." + LedgerEntries.TYPE + " = :type");
                params.put("type", filter.type().name());
            }
            if (filter.from() != null) {
                conditions.add("e." + LedgerEntries.CREATED_AT + " >= :from");
                params.put("from", OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
            }
            if (filter.to() != null) {
                conditions.add("e." + LedgerEntries.CREATED_AT + " <= :to");
                params.put("to", OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
            }
            return new Where(" WHERE " + String.join(" AND ", conditions), params);
        }
    }
}
