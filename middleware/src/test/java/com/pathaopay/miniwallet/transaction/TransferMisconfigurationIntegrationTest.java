package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.common.constant.DbConstants.Fees;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.request.TransferRequest;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.service.WalletService;
import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A transfer cannot run without its fee row; nothing may be written and the key stays free. */
class TransferMisconfigurationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WalletService walletService;

    @Test
    void aMissingFeeRowFailsTheTransferWithoutWritingAnythingAndTheKeyStaysUsable() {
        String alice = walletService.createWallet(
                new CreateWalletRequest(UUID.randomUUID(), "01711000001", WalletType.LIABILITY)).phone();
        String bob = walletService.createWallet(
                new CreateWalletRequest(UUID.randomUUID(), "01711000002", WalletType.LIABILITY)).phone();
        topUp(alice, 50000, "fund");
        long fee = jdbcClient.sql("SELECT " + Fees.AMOUNT + " FROM " + Fees.TABLE + " WHERE "
                + Fees.TRANSACTION_TYPE + " = 'TRANSFER'").query(Long.class).single();
        jdbcClient.sql("DELETE FROM " + Fees.TABLE + " WHERE " + Fees.TRANSACTION_TYPE + " = 'TRANSFER'").update();
        try {
            assertThrows(IllegalStateException.class,
                    () -> transactionService.transfer(new TransferRequest(alice, bob, 10000L, null), "t1"));
        } finally {
            jdbcClient.sql("INSERT INTO " + Fees.TABLE + " (" + Fees.TRANSACTION_TYPE + ", " + Fees.AMOUNT
                    + ") VALUES ('TRANSFER', :fee)").param("fee", fee).update();
        }

        var response = transactionService.transfer(new TransferRequest(alice, bob, 10000L, null), "t1");

        assertEquals(10000L, response.amount());
        assertEquals(fee, response.fee());
    }
}
