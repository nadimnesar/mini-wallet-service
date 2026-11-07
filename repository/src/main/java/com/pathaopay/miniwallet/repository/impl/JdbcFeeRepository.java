package com.pathaopay.miniwallet.repository.impl;

import com.pathaopay.miniwallet.common.constant.DbConstants.Fees;
import com.pathaopay.miniwallet.model.enums.TransactionType;
import com.pathaopay.miniwallet.repository.FeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.OptionalLong;

@Repository
@RequiredArgsConstructor
public class JdbcFeeRepository implements FeeRepository {

    private static final String FIND_AMOUNT =
            "SELECT " + Fees.AMOUNT + " FROM " + Fees.TABLE + " WHERE " + Fees.TRANSACTION_TYPE + " = :type";

    private final JdbcClient jdbcClient;

    @Override
    public OptionalLong findAmount(TransactionType type) {
        return jdbcClient.sql(FIND_AMOUNT).param("type", type.name()).query(Long.class).optional()
                .map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }
}
