package com.pathaopay.miniwallet.repository.impl;

import com.pathaopay.miniwallet.common.constant.DbConstants.IdempotencyKeys;
import com.pathaopay.miniwallet.model.IdempotencyRecord;
import com.pathaopay.miniwallet.repository.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** JDBC storage of idempotency keys; a claim is an insert that does nothing on a key conflict. */
@Repository
@RequiredArgsConstructor
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    // If another transaction holds an uncommitted row for this key, PostgreSQL makes this INSERT wait
    // for it: after a commit it does nothing (key used), after a rollback it inserts (key claimed).
    private static final String TRY_CLAIM =
            "INSERT INTO " + IdempotencyKeys.TABLE + " (" + String.join(", ",
                    IdempotencyKeys.IDEMPOTENCY_KEY, IdempotencyKeys.OPERATION, IdempotencyKeys.REQUEST_HASH) + ")"
                    + " VALUES (:key, :operation, :requestHash)"
                    + " ON CONFLICT (" + IdempotencyKeys.IDEMPOTENCY_KEY + ") DO NOTHING";

    private static final String FIND_BY_KEY =
            "SELECT " + String.join(", ",
                    IdempotencyKeys.IDEMPOTENCY_KEY, IdempotencyKeys.OPERATION, IdempotencyKeys.REQUEST_HASH,
                    IdempotencyKeys.RESOURCE_ID, IdempotencyKeys.ERROR_CODE, IdempotencyKeys.ERROR_MESSAGE)
                    + " FROM " + IdempotencyKeys.TABLE
                    + " WHERE " + IdempotencyKeys.IDEMPOTENCY_KEY + " = :key";

    private static final String COMPLETE_WITH_RESOURCE =
            "UPDATE " + IdempotencyKeys.TABLE
                    + " SET " + IdempotencyKeys.RESOURCE_ID + " = :resourceId, "
                    + IdempotencyKeys.COMPLETED_AT + " = now()"
                    + " WHERE " + IdempotencyKeys.IDEMPOTENCY_KEY + " = :key";

    private static final String COMPLETE_WITH_ERROR =
            "UPDATE " + IdempotencyKeys.TABLE
                    + " SET " + IdempotencyKeys.ERROR_CODE + " = :errorCode, "
                    + IdempotencyKeys.ERROR_MESSAGE + " = :errorMessage, "
                    + IdempotencyKeys.COMPLETED_AT + " = now()"
                    + " WHERE " + IdempotencyKeys.IDEMPOTENCY_KEY + " = :key";

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString(IdempotencyKeys.IDEMPOTENCY_KEY),
            rs.getString(IdempotencyKeys.OPERATION),
            rs.getString(IdempotencyKeys.REQUEST_HASH),
            rs.getObject(IdempotencyKeys.RESOURCE_ID, UUID.class),
            rs.getString(IdempotencyKeys.ERROR_CODE),
            rs.getString(IdempotencyKeys.ERROR_MESSAGE));

    private final JdbcClient jdbcClient;

    @Override
    public boolean tryClaim(String key, String operation, String requestHash) {
        return jdbcClient.sql(TRY_CLAIM)
                .param("key", key)
                .param("operation", operation)
                .param("requestHash", requestHash)
                .update() == 1;
    }

    @Override
    public Optional<IdempotencyRecord> findByKey(String key) {
        return jdbcClient.sql(FIND_BY_KEY).param("key", key).query(ROW_MAPPER).optional();
    }

    @Override
    public void completeWithResource(String key, UUID resourceId) {
        jdbcClient.sql(COMPLETE_WITH_RESOURCE).param("key", key).param("resourceId", resourceId).update();
    }

    @Override
    public void completeWithError(String key, String errorCode, String errorMessage) {
        jdbcClient.sql(COMPLETE_WITH_ERROR)
                .param("key", key)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .update();
    }
}
