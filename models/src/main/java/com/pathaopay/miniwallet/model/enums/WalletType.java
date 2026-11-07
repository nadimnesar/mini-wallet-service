package com.pathaopay.miniwallet.model.enums;

/**
 * Account class of a ledger wallet (the five classes of double-entry bookkeeping). Customer wallets are
 * {@code LIABILITY}: the balance is money the platform owes the customer. System accounts are seeded by Liquibase.
 * ASSET and EXPENSE accounts are debit-normal (a debit raises the balance); LIABILITY, EQUITY and REVENUE accounts
 * are credit-normal.
 */
public enum WalletType {
    ASSET(EntryDirection.DEBIT),
    EXPENSE(EntryDirection.DEBIT),
    REVENUE(EntryDirection.CREDIT),
    EQUITY(EntryDirection.CREDIT),
    LIABILITY(EntryDirection.CREDIT);

    private final EntryDirection normalSide;

    WalletType(EntryDirection normalSide) {
        this.normalSide = normalSide;
    }

    public EntryDirection normalSide() {
        return normalSide;
    }

    /** The balance after posting {@code amount} on the given side: it grows on the normal side, shrinks on the other. */
    public long apply(long balance, EntryDirection direction, long amount) {
        return direction == normalSide ? balance + amount : balance - amount;
    }
}
