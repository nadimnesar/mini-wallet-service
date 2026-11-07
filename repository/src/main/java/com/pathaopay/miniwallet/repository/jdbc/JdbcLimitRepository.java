package com.pathaopay.miniwallet.repository.jdbc;

import com.pathaopay.miniwallet.common.constant.DbConstants.Limits;
import com.pathaopay.miniwallet.model.TransactionLimit;
import com.pathaopay.miniwallet.model.TransactionType;
import com.pathaopay.miniwallet.repository.LimitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcLimitRepository implements LimitRepository {

    private static final String FIND =
            "SELECT " + Limits.MIN_AMOUNT + ", " + Limits.MAX_AMOUNT + " FROM " + Limits.TABLE
                    + " WHERE " + Limits.TRANSACTION_TYPE + " = :type";

    private final JdbcClient jdbcClient;

    @Override
    public Optional<TransactionLimit> find(TransactionType type) {
        return jdbcClient.sql(FIND).param("type", type.name())
                .query((rs, rowNum) -> new TransactionLimit(rs.getLong(Limits.MIN_AMOUNT), rs.getLong(Limits.MAX_AMOUNT)))
                .optional();
    }
}
