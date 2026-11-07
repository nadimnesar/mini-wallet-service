package com.pathaopay.miniwallet.ledger;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.DbConstants.LedgerEntries;
import com.pathaopay.miniwallet.common.constant.DbConstants.Transactions;
import com.pathaopay.miniwallet.common.constant.DbConstants.Wallets;
import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.service.WalletService;
import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the double-entry rules are enforced by PostgreSQL itself, independent of application code: every journal
 * balances (debits equal credits), every chain starts at 0, continues exactly from the previous balance, never forks,
 * never goes negative, keeps the arithmetic of its account class, and entries can never be changed.
 */
class LedgerIntegrityIntegrationTest extends AbstractIntegrationTest {

    private static final UUID CLEARING = AppConstants.GATEWAY_CLEARING_WALLET_ID;
    private static final UUID REVENUE = AppConstants.REVENUE_WALLET_ID;

    @Autowired
    private WalletService walletService;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void createWallets() {
        alice = idOf(walletService.createWallet(new CreateWalletRequest(user("u_alice"), "01711000001")).phone());
        bob = idOf(walletService.createWallet(new CreateWalletRequest(user("u_bob"), "01811000002")).phone());
    }

    @Test
    void newWalletHasZeroBalanceAndNoLedgerEntries() {
        assertEquals(0, apiBalance(alice));
        assertEquals(0, entryCount(alice));
    }

    @Test
    void acceptsBalancedJournalsAndDerivesBalancesFromTheChains() {
        addMoney(alice, 1, 50000, 0, "GW-1");
        addMoney(alice, 2, 20000, 50000, "GW-2");
        journal(
                debit(alice, "LIABILITY", 3, "TRANSFER_OUT", 30000, 70000, 40000, bob, "T-1"),
                credit(bob, "LIABILITY", 1, "TRANSFER_IN", 30000, 0, 30000, alice, "T-1"));

        assertEquals(40000, apiBalance(alice));
        assertEquals(30000, apiBalance(bob));
        assertEquals(70000, apiBalance(CLEARING));
        assertEquals(70000, balance(CLEARING));
    }

    @Test
    void trialBalanceHolds() {
        addMoney(alice, 1, 50000, 0, "GW-1");
        journal(
                debit(alice, "LIABILITY", 2, "TRANSFER_OUT", 10000, 50000, 40000, bob, "T-1"),
                debit(alice, "LIABILITY", 3, "FEE_OUT", 500, 40000, 39500, REVENUE, "T-1"),
                credit(bob, "LIABILITY", 1, "TRANSFER_IN", 10000, 0, 10000, alice, "T-1"),
                credit(REVENUE, "REVENUE", 1, "FEE_IN", 500, 0, 500, alice, "T-1"));

        long debitNormal = sum("wallet_type IN ('ASSET', 'EXPENSE')");
        long creditNormal = sum("wallet_type IN ('LIABILITY', 'EQUITY', 'REVENUE')");
        assertEquals(50000, debitNormal);
        assertEquals(debitNormal, creditNormal);
    }

    @Test
    void journalMustBalance() {
        assertRejected(() -> journal(
                credit(alice, "LIABILITY", 1, "ADD_MONEY", 50000, 0, 50000, CLEARING, "GW-1")));
        assertRejected(() -> journal(
                credit(alice, "LIABILITY", 1, "ADD_MONEY", 50000, 0, 50000, CLEARING, "GW-1"),
                debit(CLEARING, "ASSET", 1, "GATEWAY_CLEARING", 40000, 0, 40000, alice, "GW-1")));
        assertEquals(0, entryCount(alice));
    }

    @Test
    void firstEntryMustStartFromZero() {
        assertRejected(() -> addMoney(alice, 1, 5000, 100, "GW-1"));
    }

    @Test
    void entryMustContinueFromThePreviousBalance() {
        addMoney(alice, 1, 50000, 0, "GW-1");

        assertRejected(() -> addMoney(alice, 2, 10000, 40000, "GW-2"));
    }

    @Test
    void chainCannotSkipAnEntry() {
        addMoney(alice, 1, 50000, 0, "GW-1");

        assertRejected(() -> addMoney(alice, 3, 10000, 50000, "GW-2"));
    }

    @Test
    void chainCannotFork() {
        addMoney(alice, 1, 50000, 0, "GW-1");
        addMoney(alice, 2, 10000, 50000, "GW-2");

        assertRejected(() -> addMoney(alice, 2, 20000, 50000, "GW-3"));
    }

    @Test
    void balanceArithmeticFollowsTheAccountClass() {
        assertRejected(() -> journal(
                credit(alice, "LIABILITY", 1, "ADD_MONEY", 50000, 0, 60000, CLEARING, "GW-1"),
                debit(CLEARING, "ASSET", 1, "GATEWAY_CLEARING", 50000, 0, 50000, alice, "GW-1")));
        assertRejected(() -> journal(
                credit(alice, "LIABILITY", 1, "ADD_MONEY", 50000, 0, 50000, CLEARING, "GW-1"),
                debit(CLEARING, "ASSET", 1, "GATEWAY_CLEARING", 50000, 0, -50000, alice, "GW-1")));
    }

    @Test
    void balanceCannotGoNegative() {
        addMoney(alice, 1, 50000, 0, "GW-1");

        assertRejected(() -> journal(
                debit(alice, "LIABILITY", 2, "TRANSFER_OUT", 60000, 50000, -10000, bob, "T-1"),
                credit(bob, "LIABILITY", 1, "TRANSFER_IN", 60000, 0, 60000, alice, "T-1")));
    }

    @Test
    void amountMustBeWithinLimits() {
        assertRejected(() -> addMoney(alice, 1, 99, 0, "GW-1"));
        assertRejected(() -> addMoney(alice, 1, 2_500_001, 0, "GW-2"));
    }

    @Test
    void gatewayReferenceCanCreditOnlyOnce() {
        addMoney(alice, 1, 50000, 0, "GW-1");

        assertRejected(() -> addMoney(bob, 1, 50000, 0, "GW-1"));
    }

    @Test
    void everyLegNeedsAnotherWalletAsCounterparty() {
        addMoney(alice, 1, 50000, 0, "GW-1");

        assertRejected(() -> journal(
                debit(alice, "LIABILITY", 2, "TRANSFER_OUT", 1000, 50000, 49000, null, "T-1"),
                credit(bob, "LIABILITY", 1, "TRANSFER_IN", 1000, 0, 1000, alice, "T-1")));
        assertRejected(() -> journal(
                debit(alice, "LIABILITY", 2, "TRANSFER_OUT", 1000, 50000, 49000, alice, "T-1"),
                credit(bob, "LIABILITY", 1, "TRANSFER_IN", 1000, 0, 1000, alice, "T-1")));
    }

    @Test
    void legTypeDeterminesItsDirectionAndAccountClass() {
        assertRejected(() -> journal(
                debit(alice, "LIABILITY", 1, "ADD_MONEY", 5000, 0, 0, CLEARING, "GW-1", "VISA"),
                credit(CLEARING, "ASSET", 1, "GATEWAY_CLEARING", 5000, 0, 0, alice, "GW-1", "VISA")));
        assertRejected(() -> journal(
                credit(alice, "LIABILITY", 1, "FEE_IN", 500, 0, 500, REVENUE, "T-1"),
                debit(REVENUE, "REVENUE", 1, "FEE_OUT", 500, 0, 0, alice, "T-1")));
    }

    @Test
    void entryMustCarryTheWalletsRealAccountType() {
        assertRejected(() -> journal(
                credit(alice, "ASSET", 1, "ADD_MONEY", 50000, 0, 50000, CLEARING, "GW-1", "VISA"),
                debit(CLEARING, "ASSET", 1, "GATEWAY_CLEARING", 50000, 0, 50000, alice, "GW-1", "VISA")));
    }

    @Test
    void addMoneyTransactionsNeedAGatewayRefAndPaymentMethodAndOthersNone() {
        assertRejected(() -> insertTransaction("ADD_MONEY", 5000, null, "VISA"));
        assertRejected(() -> insertTransaction("ADD_MONEY", 5000, UUID.randomUUID(), null));
        assertRejected(() -> insertTransaction("ADD_MONEY", 5000, UUID.randomUUID(), "AMEX"));
        assertRejected(() -> insertTransaction("TRANSFER", 5000, UUID.randomUUID(), null));
        assertRejected(() -> insertTransaction("TRANSFER", 5000, null, "VISA"));
        assertRejected(() -> insertTransaction("REFUND", 5000, null, null));
    }

    @Test
    void transactionAmountMustBeWithinLimits() {
        assertRejected(() -> insertTransaction("TRANSFER", 99, null, null));
        assertRejected(() -> insertTransaction("TRANSFER", 2_500_001, null, null));
    }

    @Test
    void transactionWithoutEntriesIsRejected() {
        assertRejected(() -> insertTransaction("TRANSFER", 5000, null, null));
        assertEquals(0, jdbcClient.sql("SELECT count(*) FROM " + Transactions.TABLE).query(Long.class).single());
    }

    @Test
    void transactionsCannotBeDeletedOrChanged() {
        addMoney(alice, 1, 50000, 0, "GW-1");

        assertRejected(() -> jdbcClient.sql("DELETE FROM " + Transactions.TABLE).update());
        assertRejected(() -> jdbcClient.sql("UPDATE " + Transactions.TABLE + " SET " + Transactions.AMOUNT + " = 100")
                .update());
    }

    @Test
    void anEntryNeedsAnExistingTransaction() {
        assertRejected(() -> jdbcClient.sql("INSERT INTO " + LedgerEntries.TABLE + " (" + String.join(", ",
                        LedgerEntries.ID, LedgerEntries.WALLET_ID, LedgerEntries.WALLET_TYPE, LedgerEntries.ENTRY_NO,
                        LedgerEntries.TYPE, LedgerEntries.DIRECTION, LedgerEntries.AMOUNT, LedgerEntries.BALANCE_BEFORE,
                        LedgerEntries.BALANCE_AFTER, LedgerEntries.COUNTERPARTY_WALLET_ID, LedgerEntries.TRANSACTION_ID)
                        + ") VALUES (:id, :w, 'LIABILITY', 1, 'ADD_MONEY', 'CREDIT', 5000, 0, 5000, :c, :tx)")
                .param("id", UUID.randomUUID()).param("w", alice).param("c", CLEARING).param("tx", UUID.randomUUID())
                .update());
    }

    @Test
    void entriesCannotBeDeletedOrChanged() {
        addMoney(alice, 1, 50000, 0, "GW-1");
        addMoney(alice, 2, 10000, 50000, "GW-2");

        assertRejected(() -> jdbcClient.sql("DELETE FROM " + LedgerEntries.TABLE
                        + " WHERE " + LedgerEntries.WALLET_ID + " = :w AND " + LedgerEntries.ENTRY_NO + " = 2")
                .param("w", alice)
                .update());
        assertRejected(() -> jdbcClient.sql("UPDATE " + LedgerEntries.TABLE + " SET "
                        + LedgerEntries.AMOUNT + " = 100 WHERE " + LedgerEntries.WALLET_ID + " = :w")
                .param("w", alice)
                .update());
        assertEquals(2, entryCount(alice));
    }

    @Test
    void walletsAreOneOfTheFiveAccountClasses() {
        assertRejected(() -> insertWallet("USER", AppConstants.SYSTEM_USER_ID, "01911000001"));
        insertWallet("ASSET", AppConstants.SYSTEM_USER_ID, "01911000002");
    }

    @Test
    void everyWalletNeedsAUniqueValidPhone() {
        assertRejected(() -> insertWallet("EXPENSE", AppConstants.SYSTEM_USER_ID, null));
        assertRejected(() -> insertWallet("EXPENSE", AppConstants.SYSTEM_USER_ID, "12345"));
        assertRejected(() -> insertWallet("EXPENSE", AppConstants.SYSTEM_USER_ID, "01711000001"));
        assertRejected(() -> insertWallet("EXPENSE", AppConstants.SYSTEM_USER_ID, AppConstants.REVENUE_WALLET_PHONE));
        assertRejected(() -> insertWallet("LIABILITY", UUID.randomUUID(), "01011000001"));
        assertRejected(() -> insertWallet("LIABILITY", UUID.randomUUID(), "01211000001"));
        insertWallet("LIABILITY", UUID.randomUUID(), "01311000001");
        insertWallet("EXPENSE", AppConstants.SYSTEM_USER_ID, "01011000009");
        insertWallet("EXPENSE", AppConstants.SYSTEM_USER_ID, "01911000003");
    }

    @Test
    void customerWalletsBelongToACustomerAndSystemAccountsToTheSystemUser() {
        assertRejected(() -> insertWallet("LIABILITY", AppConstants.SYSTEM_USER_ID, "01911000004"));
        assertRejected(() -> insertWallet("EXPENSE", UUID.randomUUID(), "01911000005"));
        insertWallet("EXPENSE", AppConstants.SYSTEM_USER_ID, "01911000006");
        insertWallet("EQUITY", AppConstants.SYSTEM_USER_ID, "01911000007");
    }

    private void insertWallet(String type, UUID userId, String phone) {
        jdbcClient.sql("INSERT INTO " + Wallets.TABLE + " (" + String.join(", ", Wallets.WALLET_ID, Wallets.USER_ID,
                        Wallets.PHONE, Wallets.TYPE) + ") VALUES (:id, :userId, :phone, :type)")
                .param("id", UUID.randomUUID())
                .param("userId", userId, Types.OTHER)
                .param("phone", phone, Types.VARCHAR)
                .param("type", type)
                .update();
    }

    private UUID idOf(String phone) {
        return jdbcClient.sql("SELECT " + Wallets.WALLET_ID + " FROM " + Wallets.TABLE + " WHERE " + Wallets.PHONE
                + " = :p").param("p", phone).query(UUID.class).single();
    }

    private long apiBalance(UUID walletId) {
        String phone = jdbcClient.sql("SELECT " + Wallets.PHONE + " FROM " + Wallets.TABLE + " WHERE "
                + Wallets.WALLET_ID + " = :id").param("id", walletId).query(String.class).single();
        return walletService.getBalance(phone).balance();
    }

    private void insertTransaction(String type, long amount, UUID gatewayRef, String paymentMethod) {
        jdbcClient.sql("INSERT INTO " + Transactions.TABLE + " (" + String.join(", ", Transactions.ID,
                        Transactions.TYPE, Transactions.AMOUNT, Transactions.GATEWAY_REF, Transactions.PAYMENT_METHOD)
                        + ") VALUES (:id, :type, :amount, :gatewayRef, :paymentMethod)")
                .param("id", UUID.randomUUID())
                .param("type", type)
                .param("amount", amount)
                .param("gatewayRef", gatewayRef, Types.OTHER)
                .param("paymentMethod", paymentMethod, Types.VARCHAR)
                .update();
    }

    private record Leg(UUID walletId, String walletType, long entryNo, String type, String direction, long amount,
                       long before, long after, UUID counterparty, String reference, String paymentMethod) {
    }

    private static Leg credit(UUID wallet, String walletType, long entryNo, String type, long amount, long before,
                              long after, UUID counterparty, String reference) {
        return credit(wallet, walletType, entryNo, type, amount, before, after, counterparty, reference, null);
    }

    private static Leg credit(UUID wallet, String walletType, long entryNo, String type, long amount, long before,
                              long after, UUID counterparty, String reference, String paymentMethod) {
        return new Leg(wallet, walletType, entryNo, type, "CREDIT", amount, before, after, counterparty, reference, paymentMethod);
    }

    private static Leg debit(UUID wallet, String walletType, long entryNo, String type, long amount, long before,
                             long after, UUID counterparty, String reference) {
        return debit(wallet, walletType, entryNo, type, amount, before, after, counterparty, reference, null);
    }

    private static Leg debit(UUID wallet, String walletType, long entryNo, String type, long amount, long before,
                             long after, UUID counterparty, String reference, String paymentMethod) {
        return new Leg(wallet, walletType, entryNo, type, "DEBIT", amount, before, after, counterparty, reference, paymentMethod);
    }

    /** Customer top-up: CREDIT the customer, DEBIT the clearing asset for the same amount (continuing its chain). */
    private void addMoney(UUID wallet, long entryNo, long amount, long before, String reference) {
        long[] clearing = latest(CLEARING);
        journal(credit(wallet, "LIABILITY", entryNo, "ADD_MONEY", amount, before, before + amount, CLEARING, reference, "VISA"),
                debit(CLEARING, "ASSET", clearing[0] + 1, "GATEWAY_CLEARING", amount, clearing[1],
                        clearing[1] + amount, wallet, reference, "VISA"));
    }

    /**
     * Inserts the transaction row and all of its legs in ONE statement (a data-modifying CTE), so the deferred
     * balance check sees the whole transaction. The transaction kind is derived from the legs' types.
     */
    private void journal(Leg... legs) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < legs.length; i++) {
            rows.add("(:id%d, :w%d, :wt%d, :n%d, :t%d, :d%d, :a%d, :b%d, :f%d, :c%d, :tx)".formatted(
                    i, i, i, i, i, i, i, i, i, i));
        }
        Leg first = legs[0];
        boolean addMoney = first.paymentMethod() != null;
        UUID transactionId = UUID.nameUUIDFromBytes(first.reference().getBytes(StandardCharsets.UTF_8));
        String kind = addMoney ? "ADD_MONEY" : "TRANSFER";
        JdbcClient.StatementSpec statement = jdbcClient.sql("WITH tx AS (INSERT INTO " + Transactions.TABLE + " ("
                + String.join(", ", Transactions.ID, Transactions.TYPE, Transactions.AMOUNT, Transactions.GATEWAY_REF,
                Transactions.PAYMENT_METHOD) + ") VALUES (:tx, '" + kind + "', :txAmount, "
                + (addMoney ? ":tx" : "NULL") + ", :txMethod)) INSERT INTO " + LedgerEntries.TABLE + " ("
                + String.join(", ", LedgerEntries.ID, LedgerEntries.WALLET_ID, LedgerEntries.WALLET_TYPE,
                LedgerEntries.ENTRY_NO, LedgerEntries.TYPE, LedgerEntries.DIRECTION,
                LedgerEntries.AMOUNT, LedgerEntries.BALANCE_BEFORE, LedgerEntries.BALANCE_AFTER,
                LedgerEntries.COUNTERPARTY_WALLET_ID, LedgerEntries.TRANSACTION_ID)
                + ") VALUES " + String.join(", ", rows))
                .param("tx", transactionId)
                .param("txAmount", first.amount())
                .param("txMethod", first.paymentMethod(), Types.VARCHAR);
        for (int i = 0; i < legs.length; i++) {
            Leg leg = legs[i];
            statement = statement
                    .param("id" + i, UUID.randomUUID())
                    .param("w" + i, leg.walletId())
                    .param("wt" + i, leg.walletType())
                    .param("n" + i, leg.entryNo())
                    .param("t" + i, leg.type())
                    .param("d" + i, leg.direction())
                    .param("a" + i, leg.amount())
                    .param("b" + i, leg.before())
                    .param("f" + i, leg.after())
                    .param("c" + i, leg.counterparty(), Types.OTHER);
        }
        statement.update();
    }

    private long[] latest(UUID walletId) {
        return jdbcClient.sql("SELECT " + LedgerEntries.ENTRY_NO + ", " + LedgerEntries.BALANCE_AFTER
                        + " FROM " + LedgerEntries.TABLE + " WHERE " + LedgerEntries.WALLET_ID
                        + " = :w ORDER BY " + LedgerEntries.ENTRY_NO + " DESC LIMIT 1")
                .param("w", walletId)
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)})
                .optional()
                .orElse(new long[]{0, 0});
    }

    private long balance(UUID walletId) {
        return latest(walletId)[1];
    }

    /** Sum of every wallet's latest balance, for the wallets matching the condition on their entries' wallet_type. */
    private long sum(String walletTypeCondition) {
        return jdbcClient.sql("SELECT COALESCE(SUM(balance_after), 0) FROM (SELECT DISTINCT ON ("
                        + LedgerEntries.WALLET_ID + ") " + LedgerEntries.WALLET_ID + ", "
                        + LedgerEntries.WALLET_TYPE + ", " + LedgerEntries.BALANCE_AFTER
                        + " FROM " + LedgerEntries.TABLE + " ORDER BY " + LedgerEntries.WALLET_ID + ", "
                        + LedgerEntries.ENTRY_NO + " DESC) latest WHERE " + walletTypeCondition)
                .query(Long.class)
                .single();
    }

    private long entryCount(UUID walletId) {
        return jdbcClient.sql("SELECT count(*) FROM " + LedgerEntries.TABLE + " WHERE " + LedgerEntries.WALLET_ID + " = :w")
                .param("w", walletId)
                .query(Long.class)
                .single();
    }

    private static void assertRejected(Runnable statement) {
        assertThrows(DataIntegrityViolationException.class, statement::run);
    }

    /** Deterministic user_id (a UUID) for a readable test name. */
    private static UUID user(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
