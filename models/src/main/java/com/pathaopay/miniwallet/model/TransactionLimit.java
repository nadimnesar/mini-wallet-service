package com.pathaopay.miniwallet.model;

/** The smallest amount and the largest total a single transaction of one type may involve, in poisha. */
public record TransactionLimit(long minAmount, long maxAmount) {
}
