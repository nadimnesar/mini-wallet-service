package com.pathaopay.miniwallet.model;

import com.pathaopay.miniwallet.model.enums.EntryDirection;
import com.pathaopay.miniwallet.model.enums.WalletType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WalletTypeTest {

    @Test
    void assetAndExpenseAreDebitNormalTheRestCreditNormal() {
        assertEquals(EntryDirection.DEBIT, WalletType.ASSET.normalSide());
        assertEquals(EntryDirection.DEBIT, WalletType.EXPENSE.normalSide());
        assertEquals(EntryDirection.CREDIT, WalletType.LIABILITY.normalSide());
        assertEquals(EntryDirection.CREDIT, WalletType.EQUITY.normalSide());
        assertEquals(EntryDirection.CREDIT, WalletType.REVENUE.normalSide());
    }

    @Test
    void aPostingGrowsTheBalanceOnTheNormalSideAndShrinksItOnTheOther() {
        assertEquals(150, WalletType.LIABILITY.apply(100, EntryDirection.CREDIT, 50));
        assertEquals(50, WalletType.LIABILITY.apply(100, EntryDirection.DEBIT, 50));
        assertEquals(150, WalletType.ASSET.apply(100, EntryDirection.DEBIT, 50));
        assertEquals(50, WalletType.ASSET.apply(100, EntryDirection.CREDIT, 50));
    }
}
