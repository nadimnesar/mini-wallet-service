package com.pathaopay.miniwallet.repository.jdbc;

import com.pathaopay.miniwallet.model.IdempotencyRecord;
import com.pathaopay.miniwallet.repository.IdempotencyRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    // If another transaction holds an uncommitted row for this key, PostgreSQL makes this INSERT wait
    // for it: after a commit it does nothing (key used), after a rollback it inserts (key claimed).
    private static final String TRY_CLAIM = """
            INSERT INTO idempotency_keys (idempotency_key, operation, request_hash)
            VALUES (:key, :operation, :requestHash)
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

    private static final String FIND_BY_KEY = """
            SELECT idempotency_key, operation, request_hash, resource_id, error_code, error_message
            FROM idempotency_keys WHERE idempotency_key = :key
            """;

    private static final String COMPLETE_WITH_RESOURCE = """
            UPDATE idempotency_keys SET resource_id = :resourceId, completed_at = now()
            WHERE idempotency_key = :key
            """;

    private static final String COMPLETE_WITH_ERROR = """
            UPDATE idempotency_keys SET error_code = :errorCode, error_message = :errorMessage, completed_at = now()
            WHERE idempotency_key = :key
            """;

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("idempotency_key"),
            rs.getString("operation"),
            rs.getString("request_hash"),
            rs.getObject("resource_id", UUID.class),
            rs.getString("error_code"),
            rs.getString("error_message"));

    private final JdbcClient jdbcClient;

    public JdbcIdempotencyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

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
