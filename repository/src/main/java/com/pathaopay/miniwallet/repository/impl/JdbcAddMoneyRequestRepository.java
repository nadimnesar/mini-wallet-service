package com.pathaopay.miniwallet.repository.impl;

import com.pathaopay.miniwallet.common.constant.DbConstants.AddMoneyRequests;
import com.pathaopay.miniwallet.model.AddMoneyRequestRecord;
import com.pathaopay.miniwallet.model.enums.AddMoneyStatus;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;
import com.pathaopay.miniwallet.repository.AddMoneyRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC access to {@code add_money_requests}, the status record and outbox of asynchronous add-money. */
@Repository
@RequiredArgsConstructor
public class JdbcAddMoneyRequestRepository implements AddMoneyRequestRepository {

    private static final String COLUMNS = String.join(", ",
            AddMoneyRequests.ID, AddMoneyRequests.WALLET_ID, AddMoneyRequests.AMOUNT, AddMoneyRequests.GATEWAY_REF,
            AddMoneyRequests.PAYMENT_METHOD, AddMoneyRequests.STATUS, AddMoneyRequests.ATTEMPTS,
            AddMoneyRequests.TRANSACTION_ID, AddMoneyRequests.ERROR_CODE, AddMoneyRequests.ERROR_MESSAGE,
            AddMoneyRequests.CREATED_AT, AddMoneyRequests.UPDATED_AT);

    // DO NOTHING without a conflict target: the partial unique index on gateway_ref (open requests only) decides.
    private static final String INSERT =
            "INSERT INTO " + AddMoneyRequests.TABLE + " (" + String.join(", ",
                    AddMoneyRequests.ID, AddMoneyRequests.WALLET_ID, AddMoneyRequests.AMOUNT,
                    AddMoneyRequests.GATEWAY_REF, AddMoneyRequests.PAYMENT_METHOD) + ")"
                    + " VALUES (:id, :walletId, :amount, :gatewayRef, :paymentMethod)"
                    + " ON CONFLICT DO NOTHING RETURNING " + COLUMNS;

    private static final String SELECT =
            "SELECT " + COLUMNS + " FROM " + AddMoneyRequests.TABLE + " WHERE " + AddMoneyRequests.ID + " = :id";

    private static final String MARK_COMPLETED =
            "UPDATE " + AddMoneyRequests.TABLE + " SET " + AddMoneyRequests.STATUS + " = 'COMPLETED', "
                    + AddMoneyRequests.TRANSACTION_ID + " = :transactionId, " + AddMoneyRequests.ATTEMPTS + " = :attempts, "
                    + AddMoneyRequests.ERROR_CODE + " = NULL, " + AddMoneyRequests.ERROR_MESSAGE + " = NULL, "
                    + AddMoneyRequests.UPDATED_AT + " = now() WHERE " + AddMoneyRequests.ID + " = :id";

    private static final String MARK_FAILED =
            "UPDATE " + AddMoneyRequests.TABLE + " SET " + AddMoneyRequests.STATUS + " = 'FAILED', "
                    + AddMoneyRequests.ATTEMPTS + " = :attempts, " + AddMoneyRequests.ERROR_CODE + " = :errorCode, "
                    + AddMoneyRequests.ERROR_MESSAGE + " = :errorMessage, " + AddMoneyRequests.UPDATED_AT + " = now()"
                    + " WHERE " + AddMoneyRequests.ID + " = :id";

    private static final String RECORD_RETRY =
            "UPDATE " + AddMoneyRequests.TABLE + " SET " + AddMoneyRequests.ATTEMPTS + " = :attempts, "
                    + AddMoneyRequests.ERROR_CODE + " = :errorCode, " + AddMoneyRequests.ERROR_MESSAGE
                    + " = :errorMessage, " + AddMoneyRequests.UPDATED_AT + " = now()"
                    + " WHERE " + AddMoneyRequests.ID + " = :id";

    private static final String LOCK_DISPATCHABLE =
            "SELECT " + AddMoneyRequests.ID + " FROM " + AddMoneyRequests.TABLE
                    + " WHERE " + AddMoneyRequests.STATUS + " = 'PENDING' AND (" + AddMoneyRequests.PUBLISHED_AT
                    + " IS NULL OR (" + AddMoneyRequests.PUBLISHED_AT
                    + " < now() - make_interval(secs => :republishSeconds) AND (" + AddMoneyRequests.ATTEMPTS
                    + " = 0 OR " + AddMoneyRequests.UPDATED_AT + " < now() - make_interval(secs => :parkedSeconds))))"
                    + " ORDER BY " + AddMoneyRequests.CREATED_AT + " LIMIT :limit FOR UPDATE SKIP LOCKED";

    private static final String MARK_PUBLISHED =
            "UPDATE " + AddMoneyRequests.TABLE + " SET " + AddMoneyRequests.PUBLISHED_AT + " = now()"
                    + " WHERE " + AddMoneyRequests.ID + " IN (:ids)";

    private static final String MARK_UNPUBLISHED =
            "UPDATE " + AddMoneyRequests.TABLE + " SET " + AddMoneyRequests.PUBLISHED_AT + " = NULL"
                    + " WHERE " + AddMoneyRequests.ID + " IN (:ids) AND " + AddMoneyRequests.STATUS + " = 'PENDING'";

    private static final RowMapper<AddMoneyRequestRecord> ROW_MAPPER = (rs, rowNum) -> new AddMoneyRequestRecord(
            rs.getObject(AddMoneyRequests.ID, UUID.class),
            rs.getObject(AddMoneyRequests.WALLET_ID, UUID.class),
            rs.getLong(AddMoneyRequests.AMOUNT),
            rs.getObject(AddMoneyRequests.GATEWAY_REF, UUID.class),
            PaymentMethod.valueOf(rs.getString(AddMoneyRequests.PAYMENT_METHOD)),
            AddMoneyStatus.valueOf(rs.getString(AddMoneyRequests.STATUS)),
            rs.getInt(AddMoneyRequests.ATTEMPTS),
            rs.getObject(AddMoneyRequests.TRANSACTION_ID, UUID.class),
            rs.getString(AddMoneyRequests.ERROR_CODE),
            rs.getString(AddMoneyRequests.ERROR_MESSAGE),
            rs.getObject(AddMoneyRequests.CREATED_AT, OffsetDateTime.class).toInstant(),
            rs.getObject(AddMoneyRequests.UPDATED_AT, OffsetDateTime.class).toInstant());

    private final JdbcClient jdbcClient;

    @Override
    public Optional<AddMoneyRequestRecord> insert(AddMoneyRequestRecord request) {
        return jdbcClient.sql(INSERT)
                .param("id", request.id())
                .param("walletId", request.walletId())
                .param("amount", request.amount())
                .param("gatewayRef", request.gatewayRef(), Types.OTHER)
                .param("paymentMethod", request.paymentMethod().name())
                .query(ROW_MAPPER).optional();
    }

    @Override
    public Optional<AddMoneyRequestRecord> findById(UUID id) {
        return jdbcClient.sql(SELECT).param("id", id).query(ROW_MAPPER).optional();
    }

    @Override
    public Optional<AddMoneyRequestRecord> lockById(UUID id) {
        return jdbcClient.sql(SELECT + " FOR UPDATE").param("id", id).query(ROW_MAPPER).optional();
    }

    @Override
    public void markCompleted(UUID id, UUID transactionId, int attempts) {
        jdbcClient.sql(MARK_COMPLETED).param("id", id).param("transactionId", transactionId)
                .param("attempts", attempts).update();
    }

    @Override
    public void markFailed(UUID id, int attempts, String errorCode, String errorMessage) {
        jdbcClient.sql(MARK_FAILED).param("id", id).param("attempts", attempts)
                .param("errorCode", errorCode).param("errorMessage", errorMessage).update();
    }

    @Override
    public void recordRetry(UUID id, int attempts, String errorCode, String errorMessage) {
        jdbcClient.sql(RECORD_RETRY).param("id", id).param("attempts", attempts)
                .param("errorCode", errorCode).param("errorMessage", errorMessage).update();
    }

    @Override
    public List<UUID> lockDispatchable(int limit, Duration republishAfter, Duration parkedAfter) {
        return jdbcClient.sql(LOCK_DISPATCHABLE)
                .param("limit", limit)
                .param("republishSeconds", republishAfter.toMillis() / 1000.0)
                .param("parkedSeconds", parkedAfter.toMillis() / 1000.0)
                .query(UUID.class).list();
    }

    @Override
    public void markPublished(List<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbcClient.sql(MARK_PUBLISHED).param("ids", ids).update();
        }
    }

    @Override
    public void markUnpublished(List<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbcClient.sql(MARK_UNPUBLISHED).param("ids", ids).update();
        }
    }
}
