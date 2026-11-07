package com.pathaopay.miniwallet.model.enums;

/** The business meaning of one ledger leg, and the side ({@link EntryDirection}) it always posts on. */
public enum EntryType {
    /** Customer wallet credited by a captured card payment. */
    ADD_MONEY(EntryDirection.CREDIT),
    /** Gateway clearing asset debited by the same card payment; the other leg of ADD_MONEY. */
    GATEWAY_CLEARING(EntryDirection.DEBIT),
    /** Receiver credited by a transfer. */
    TRANSFER_IN(EntryDirection.CREDIT),
    /** Sender debited by a transfer (the principal). */
    TRANSFER_OUT(EntryDirection.DEBIT),
    /** Revenue account credited with the transfer fee. */
    FEE_IN(EntryDirection.CREDIT),
    /** Sender debited the transfer fee, on top of the amount. */
    FEE_OUT(EntryDirection.DEBIT);

    private final EntryDirection direction;

    EntryType(EntryDirection direction) {
        this.direction = direction;
    }

    /** The side this leg type always posts on. */
    public EntryDirection direction() {
        return direction;
    }
}
