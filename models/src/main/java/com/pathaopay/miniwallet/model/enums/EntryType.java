package com.pathaopay.miniwallet.model.enums;

/** The business meaning of one ledger leg, and the side ({@link EntryDirection}) it always posts on. */
public enum EntryType {
    /** Customer wallet credited by a captured card payment. */
    ADD_MONEY(EntryDirection.CREDIT),
    /** Gateway clearing asset debited by the same card payment; the other leg of ADD_MONEY. */
    GATEWAY_CLEARING(EntryDirection.DEBIT),
    TRANSFER_IN(EntryDirection.CREDIT),
    TRANSFER_OUT(EntryDirection.DEBIT),
    FEE_IN(EntryDirection.CREDIT),
    FEE_OUT(EntryDirection.DEBIT);

    private final EntryDirection direction;

    EntryType(EntryDirection direction) {
        this.direction = direction;
    }

    public EntryDirection direction() {
        return direction;
    }
}
