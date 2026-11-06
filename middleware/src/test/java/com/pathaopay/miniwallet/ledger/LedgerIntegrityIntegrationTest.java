package com.pathaopay.miniwallet.ledger;

import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.service.WalletService;
import com.pathaopay.miniwallet.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the ledger rules are enforced by PostgreSQL itself, independent of application code:
 * every chain starts at 0, continues exactly from the previous balance, never forks, never goes
 * negative and keeps the arithmetic and money rules consistent.
 */
class LedgerIntegrityIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private WalletService walletService;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void createWallets() {
        alice = walletService.createWallet(new CreateWalletRequest("u_alice", "01711000001")).walletId();
        bob = walletService.createWallet(new CreateWalletRequest("u_bob", "01811000002")).walletId();
    }

    @Test
    void newWalletHasZeroBalanceAndNoLedgerEntries() {
        assertEquals(0, walletService.getWallet(alice).balance());
        assertEquals(0, entryCount(alice));
    }

    @Test
    void acceptsAConsistentChainAndDerivesTheBalanceFromIt() {
        insert(alice, 1, "ADD_MONEY", 50000, 0, 50000, null, "GW-1");
        insert(alice, 2, "ADD_MONEY", 20000, 50000, 70000, null, "GW-2");
        insert(alice, 3, "TRANSFER_OUT", 30000, 70000, 40000, bob, "T-1");
        insert(bob, 1, "TRANSFER_IN", 30000, 0, 30000, alice, "T-1");

        assertEquals(40000, walletService.getWallet(alice).balance());
        assertEquals(30000, walletService.getWallet(bob).balance());
    }

    @Test
    void firstEntryMustStartFromZero() {
        assertRejected(() -> insert(alice, 1, "ADD_MONEY", 5000, 100, 5100, null, "GW-1"));
    }

    @Test
    void entryMustContinueFromThePreviousBalance() {
        insert(alice, 1, "ADD_MONEY", 50000, 0, 50000, null, "GW-1");

        assertRejected(() -> insert(alice, 2, "ADD_MONEY", 10000, 40000, 50000, null, "GW-2"));
    }

    @Test
    void chainCannotSkipAnEntry() {
        insert(alice, 1, "ADD_MONEY", 50000, 0, 50000, null, "GW-1");

        assertRejected(() -> insert(alice, 3, "ADD_MONEY", 10000, 50000, 60000, null, "GW-2"));
    }

    @Test
    void chainCannotFork() {
        insert(alice, 1, "ADD_MONEY", 50000, 0, 50000, null, "GW-1");
        insert(alice, 2, "ADD_MONEY", 10000, 50000, 60000, null, "GW-2");

        assertRejected(() -> insert(alice, 2, "ADD_MONEY", 20000, 50000, 70000, null, "GW-3"));
    }

    @Test
    void balanceArithmeticMustAddUp() {
        assertRejected(() -> insert(alice, 1, "ADD_MONEY", 50000, 0, 60000, null, "GW-1"));
    }

    @Test
    void balanceCannotGoNegative() {
        insert(alice, 1, "ADD_MONEY", 50000, 0, 50000, null, "GW-1");

        assertRejected(() -> insert(alice, 2, "TRANSFER_OUT", 60000, 50000, -10000, bob, "T-1"));
    }

    @Test
    void amountMustBeWithinLimits() {
        assertRejected(() -> insert(alice, 1, "ADD_MONEY", 99, 0, 99, null, "GW-1"));
        assertRejected(() -> insert(alice, 1, "ADD_MONEY", 2_500_001, 0, 2_500_001, null, "GW-2"));
    }

    @Test
    void gatewayReferenceCanCreditOnlyOnce() {
        insert(alice, 1, "ADD_MONEY", 50000, 0, 50000, null, "GW-1");

        assertRejected(() -> insert(bob, 1, "ADD_MONEY", 50000, 0, 50000, null, "GW-1"));
    }

    @Test
    void transfersNeedAnotherWalletAsCounterparty() {
        insert(alice, 1, "ADD_MONEY", 50000, 0, 50000, null, "GW-1");

        assertRejected(() -> insert(alice, 2, "TRANSFER_OUT", 1000, 50000, 49000, null, "T-1"));
        assertRejected(() -> insert(alice, 2, "TRANSFER_OUT", 1000, 50000, 49000, alice, "T-1"));
    }

    @Test
    void earlierEntriesCannotBeDeleted() {
        insert(alice, 1, "ADD_MONEY", 50000, 0, 50000, null, "GW-1");
        insert(alice, 2, "ADD_MONEY", 10000, 50000, 60000, null, "GW-2");

        assertRejected(() -> jdbcClient.sql("DELETE FROM wallet_transactions WHERE wallet_id = :w AND entry_no = 1")
                .param("w", alice)
                .update());
    }

    private void insert(UUID walletId, long entryNo, String type, long amount, long before, long after,
                        UUID counterparty, String referenceId) {
        jdbcClient.sql("""
                        INSERT INTO wallet_transactions (id, wallet_id, entry_no, type, amount, balance_before,
                                                         balance_after, counterparty_wallet_id, reference_id)
                        VALUES (:id, :walletId, :entryNo, :type, :amount, :before, :after, :counterparty, :ref)
                        """)
                .param("id", UUID.randomUUID())
                .param("walletId", walletId)
                .param("entryNo", entryNo)
                .param("type", type)
                .param("amount", amount)
                .param("before", before)
                .param("after", after)
                .param("counterparty", counterparty, java.sql.Types.OTHER)
                .param("ref", referenceId)
                .update();
    }

    private long entryCount(UUID walletId) {
        return jdbcClient.sql("SELECT count(*) FROM wallet_transactions WHERE wallet_id = :w")
                .param("w", walletId)
                .query(Long.class)
                .single();
    }

    private static void assertRejected(Runnable statement) {
        assertThrows(DataIntegrityViolationException.class, statement::run);
    }
}
