package com.pathaopay.miniwallet.repository;

import com.pathaopay.miniwallet.model.enums.TransactionType;

import java.util.OptionalLong;

/** Read access to the flat fees configured in the {@code fees} table. */
public interface FeeRepository {

    /** The fee in poisha currently charged on top of a transaction of this type, if one is configured. */
    OptionalLong findAmount(TransactionType type);
}
