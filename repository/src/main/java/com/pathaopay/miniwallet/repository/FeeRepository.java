package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.enums.TransactionType;

import java.util.OptionalLong;

public interface FeeRepository {

    /** The fee in poisha currently charged on top of a transaction of this type, if one is configured. */
    OptionalLong findAmount(TransactionType type);
}
