package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.DbConstants.Fees;
import com.pathaopay.miniwallet.common.constant.DbConstants.LedgerEntries;
import com.pathaopay.miniwallet.common.constant.DbConstants.Limits;
import com.pathaopay.miniwallet.dto.request.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.service.TransactionService;
import com.pathaopay.miniwallet.service.WalletService;
import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.pathaopay.miniwallet.common.constant.DbConstants.Transactions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end P2P transfers over HTTP against real PostgreSQL: atomicity, locking and idempotency. */
class TransferIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransactionService transactionService;

    private RestClient api;
    private String alice;
    private String bob;
    private static final String REVENUE = AppConstants.REVENUE_WALLET_PHONE;

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port);
        alice = wallet("01711000001");
        bob = wallet("01711000002");
        fund(alice, 50000);
    }

    @Test
    void movesMoneyChargesTheFeeAndWritesAllLedgerLegs() {
        Response response = transfer("key-1", alice, bob, 10000, "Lunch");

        assertEquals(201, response.status());
        assertEquals(alice, response.body().get("from_phone").asString());
        assertEquals(bob, response.body().get("to_phone").asString());
        assertEquals(7, UUID.fromString(response.body().get("from_wallet_id").asString()).version());
        assertEquals(7, UUID.fromString(response.body().get("to_wallet_id").asString()).version());
        assertEquals(walletService.getWallet(alice).walletId().toString(), response.body().get("from_wallet_id").asString());
        assertEquals(walletService.getWallet(bob).walletId().toString(), response.body().get("to_wallet_id").asString());
        assertEquals(10000, response.body().get("amount").asLong());
        assertEquals(500, response.body().get("fee").asLong());
        assertEquals("Lunch", response.body().get("note").asString());
        assertEquals("COMPLETED", response.body().get("status").asString());
        assertEquals(39500, balance(alice));
        assertEquals(10000, balance(bob));
        assertEquals(500, balance(REVENUE));

        UUID transferId = UUID.fromString(response.body().get("transfer_id").asString());
        assertEquals(4, jdbcClient.sql("SELECT count(*) FROM " + LedgerEntries.TABLE + " WHERE "
                + LedgerEntries.TRANSACTION_ID + " = :id").param("id", transferId).query(Long.class).single());
    }

    @Test
    void sameKeyAndBodyReplaysOriginalResponse() {
        Response first = transfer("key-1", alice, bob, 10000, "Lunch");
        Response replay = transfer("key-1", alice, bob, 10000, "Lunch");

        assertEquals(201, replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(39500, balance(alice));
        assertEquals(10000, balance(bob));
        assertEquals("Lunch", replay.body().get("note").asString());
    }

    @Test
    void feeIsReadFromTheFeesTableAndAReplayKeepsTheFeeThatWasCharged() {
        Response first = transfer("key-1", alice, bob, 10000, null);
        assertEquals(500, first.body().get("fee").asLong());

        setTransferFee(700);
        try {
            Response second = transfer("key-2", alice, bob, 10000, null);
            Response replay = transfer("key-1", alice, bob, 10000, null);

            assertEquals(700, second.body().get("fee").asLong());
            assertEquals(first.body(), replay.body(), "a replay returns the fee of the original transfer");
            assertEquals(50000 - 10500 - 10700, balance(alice));
            assertEquals(1200, balance(REVENUE));
        } finally {
            setTransferFee(500);
        }
    }

    @Test
    void transferNeedsBalanceForTheConfiguredFee() {
        setTransferFee(2000);
        try {
            assertError(transfer("key-1", alice, bob, 48500, null), 422, "INSUFFICIENT_BALANCE");
            assertEquals(201, transfer("key-2", alice, bob, 48000, null).status());
        } finally {
            setTransferFee(500);
        }
    }

    private void setTransferFee(long poisha) {
        jdbcClient.sql("UPDATE " + Fees.TABLE + " SET " + Fees.AMOUNT + " = :amount WHERE "
                + Fees.TRANSACTION_TYPE + " = 'TRANSFER'").param("amount", poisha).update();
    }

    @Test
    void noteIsOptionalAndMustNotBeEmptyOrTooLong() {
        assertTrue(transfer("key-1", alice, bob, 10000, null).body().get("note").isNull());
        assertError(transfer("key-2", alice, bob, 10000, ""), 400, "VALIDATION_ERROR");
        assertError(transfer("key-3", alice, bob, 10000, "x".repeat(256)), 400, "VALIDATION_ERROR");
        assertEquals(201, transfer("key-4", alice, bob, 10000, "x".repeat(255)).status());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        transfer("key-1", alice, bob, 10000, "Lunch");
        Response reused = transfer("key-1", alice, bob, 20000, "Lunch");

        assertError(reused, 422, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(39500, balance(alice));
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        assertError(transfer(null, alice, bob, 10000, null), 400, "MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void insufficientBalanceIsRejectedAndReplayedWithoutChangingBalances() {
        Response first = transfer("key-1", alice, bob, 60000, null);
        Response replay = transfer("key-1", alice, bob, 60000, null);

        assertError(first, 422, "INSUFFICIENT_BALANCE");
        assertEquals(first.body(), replay.body());
        assertEquals(50000, balance(alice));
        assertEquals(0, balance(bob));
    }

    @Test
    void senderNeedsBalanceForAmountPlusFee() {
        assertError(transfer("key-1", alice, bob, 49501, null), 422, "INSUFFICIENT_BALANCE");
        assertEquals(50000, balance(alice));

        assertEquals(201, transfer("key-2", alice, bob, 49500, null).status());
        assertEquals(0, balance(alice));
        assertEquals(500, balance(REVENUE));
    }

    @Test
    void walletsHaveTheirAccountClass() {
        assertEquals(WalletType.REVENUE, walletService.getWallet(REVENUE).type());
        assertEquals(WalletType.ASSET, walletService.getWallet(AppConstants.GATEWAY_CLEARING_WALLET_PHONE).type());
        assertEquals(WalletType.EXPENSE, walletService.getWallet(AppConstants.OPERATING_EXPENSE_WALLET_PHONE).type());
        assertEquals(WalletType.EQUITY, walletService.getWallet(AppConstants.OWNER_EQUITY_WALLET_PHONE).type());
        assertEquals(WalletType.LIABILITY, walletService.getWallet(alice).type());
        // Nothing is seeded: only the clearing asset has moved, by alice's funding in setUp.
        for (String system : List.of(REVENUE, AppConstants.OPERATING_EXPENSE_WALLET_PHONE,
                AppConstants.OWNER_EQUITY_WALLET_PHONE)) {
            assertEquals(0, balance(system));
        }
        assertEquals(50000, balance(AppConstants.GATEWAY_CLEARING_WALLET_PHONE));
        assertEquals(AppConstants.SYSTEM_USER_ID, walletService.getWallet(REVENUE).userId());
    }

    @Test
    void systemAccountsCannotSendOrReceiveTransfers() {
        for (String system : List.of(REVENUE, AppConstants.GATEWAY_CLEARING_WALLET_PHONE,
                AppConstants.OPERATING_EXPENSE_WALLET_PHONE,
                AppConstants.OWNER_EQUITY_WALLET_PHONE)) {
            assertError(transfer("from-" + system, system, bob, 10000, null), 422, "SYSTEM_WALLET_NOT_ALLOWED");
            assertError(transfer("to-" + system, alice, system, 10000, null), 422, "SYSTEM_WALLET_NOT_ALLOWED");
        }
        assertEquals(50000, balance(alice));
        assertEquals(0, balance(REVENUE));
    }

    @Test
    void everyTransferJournalBalancesAndTheTrialBalanceHolds() {
        transfer("key-1", alice, bob, 10000, null);
        transfer("key-2", bob, alice, 3000, null);

        assertEquals(0, jdbcClient.sql("SELECT COALESCE(SUM(CASE " + LedgerEntries.DIRECTION
                        + " WHEN 'DEBIT' THEN " + LedgerEntries.AMOUNT + " ELSE -" + LedgerEntries.AMOUNT
                        + " END), 0) FROM " + LedgerEntries.TABLE)
                .query(Long.class).single());
        // Customer liabilities + revenue = what the gateway owes the platform.
        assertEquals(balance(AppConstants.GATEWAY_CLEARING_WALLET_PHONE),
                balance(alice) + balance(bob) + balance(REVENUE));
    }

    @Test
    void transferringToTheSameWalletIsRejected() {
        assertError(transfer("key-1", alice, alice, 10000, null), 422, "SAME_WALLET_TRANSFER");
        assertEquals(50000, balance(alice));
    }

    @Test
    void unknownWalletsAreRejected() {
        assertError(transfer("key-1", "01999999999", bob, 10000, null), 404, "WALLET_NOT_FOUND");
        assertError(transfer("key-2", alice, "01999999998", 10000, null), 404, "WALLET_NOT_FOUND");
        assertEquals(50000, balance(alice));
    }

    @Test
    void amountOutsideLimitsIsRejected() {
        assertError(transfer("key-1", alice, bob, 99, null), 400, "INVALID_AMOUNT");
        assertError(transfer("key-2", alice, bob, 2_500_001, null), 400, "INVALID_AMOUNT");
    }

    @Test
    void amountPlusFeeMustStayWithinTheSingleTransactionLimit() {
        fund(alice, 2_500_000);

        Response tooMuch = transfer("key-1", alice, bob, 2_500_000, null);
        assertError(tooMuch, 400, "INVALID_AMOUNT");
        assertEquals("amount plus the transfer fee of 500 poisha (5 BDT) must be at most 2500000 poisha (25000 BDT), "
                + "so amount must be at most 2499500 poisha (24995 BDT)", tooMuch.body().get("error").get("message").asString());
        assertEquals(2_550_000, balance(alice));

        Response atTheLimit = transfer("key-2", alice, bob, 2_499_500, null);
        assertEquals(201, atTheLimit.status());
        assertEquals(500, atTheLimit.body().get("fee").asLong());
        assertEquals(2_550_000 - 2_500_000, balance(alice));
    }

    @Test
    void limitIsReadFromTheLimitsTableAndFollowsTheFee() {
        setTransferLimit(1000, 20000);
        setTransferFee(1000);
        try {
            assertError(transfer("key-1", alice, bob, 999, null), 400, "INVALID_AMOUNT");
            assertError(transfer("key-2", alice, bob, 19001, null), 400, "INVALID_AMOUNT");
            assertEquals(201, transfer("key-3", alice, bob, 19000, null).status());
        } finally {
            setTransferFee(500);
            setTransferLimit(100, 2_500_000);
        }
    }

    private void setTransferLimit(long min, long max) {
        jdbcClient.sql("UPDATE " + Limits.TABLE + " SET " + Limits.MIN_AMOUNT + " = :min, " + Limits.MAX_AMOUNT
                + " = :max WHERE " + Limits.TRANSACTION_TYPE + " = 'TRANSFER'")
                .param("min", min).param("max", max).update();
    }

    @Test
    void amountMustBeAnExactInteger() {
        int n = 0;
        for (String amount : List.of("10000.9", "10000.0", "\"10000\"", "1e4")) {
            assertError(send("key-" + n++, """
                    {"from_phone": "%s", "to_phone": "%s", "amount": %s}""".formatted(alice, bob, amount)),
                    400, "INVALID_PAYLOAD");
        }
        assertEquals(0, jdbcClient.sql("SELECT count(*) FROM " + Transactions.TABLE + " WHERE "
                + Transactions.TYPE + " = 'TRANSFER'").query(Long.class).single());
    }

    @Test
    void phonesMustBeValidAndAreNormalized() {
        assertError(transfer("key-1", "w_aaa", bob, 10000, null), 400, "VALIDATION_ERROR");
        assertError(transfer("key-2", alice, "12345", 10000, null), 400, "VALIDATION_ERROR");
        assertError(send("key-3", """
                {"to_phone": "%s", "amount": 10000}""".formatted(bob)), 400, "VALIDATION_ERROR");

        assertEquals(201, transfer("key-4", "+880" + alice.substring(1), "1" + bob.substring(2), 10000, null).status());
        assertEquals(10000, balance(bob));
    }

    @Test
    void sameKeyWithTheSamePhoneInAnotherFormatReplays() {
        Response first = transfer("key-1", alice, bob, 10000, null);
        Response replay = transfer("key-1", "+880" + alice.substring(1), bob, 10000, null);

        assertEquals(first.body(), replay.body());
        assertEquals(39500, balance(alice));
    }

    @Test
    void concurrentRequestsWithSameKeyExecuteOnce() throws Exception {
        List<Response> responses = concurrently(20, i -> transfer("key-1", alice, bob, 10000, null));

        Set<JsonNode> bodies = new HashSet<>();
        responses.forEach(r -> {
            assertEquals(201, r.status());
            bodies.add(r.body());
        });
        assertEquals(1, bodies.size());
        assertEquals(39500, balance(alice));
        assertEquals(10000, balance(bob));
    }

    @Test
    void concurrentTransfersCannotOverdrawTheSender() throws Exception {
        List<Response> responses = concurrently(10, i -> transfer("key-" + i, alice, bob, 10000, null));

        assertEquals(4, responses.stream().filter(r -> r.status() == 201).count());
        responses.stream().filter(r -> r.status() != 201).forEach(r -> assertError(r, 422, "INSUFFICIENT_BALANCE"));
        assertEquals(8000, balance(alice));
        assertEquals(40000, balance(bob));
        assertEquals(2000, balance(REVENUE));
    }

    @Test
    void oppositeTransfersBetweenTheSameWalletsDoNotDeadlockAndConserveMoney() throws Exception {
        fund(bob, 50000);
        List<Response> responses = concurrently(20, i -> i % 2 == 0
                ? transfer("key-" + i, alice, bob, 1000, null)
                : transfer("key-" + i, bob, alice, 1000, null));

        responses.forEach(r -> assertEquals(201, r.status(), r.body().toString()));
        assertEquals(90000, balance(alice) + balance(bob));
        assertEquals(45000, balance(alice));
        assertEquals(10000, balance(REVENUE));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private record Response(int status, JsonNode body) {
    }

    private String wallet(String phone) {
        return walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), phone)).phone();
    }

    private void fund(String phone, long amount) {
        transactionService.addMoney(new AddMoneyRequest(phone, amount, UUID.randomUUID(), PaymentMethod.VISA),
                "fund-" + UUID.randomUUID());
    }

    private long balance(String phone) {
        return walletService.getBalance(phone).balance();
    }

    private Response transfer(String key, String from, String to, long amount, String note) {
        return send(key, """
                {"from_phone": "%s", "to_phone": "%s", "amount": %d%s}"""
                .formatted(from, to, amount, note == null ? "" : ", \"note\": \"" + note + "\""));
    }

    private Response send(String key, String body) {
        return api.post()
                .uri("/api/v1/transfers")
                .headers(h -> {
                    if (key != null) {
                        h.set("Idempotency-Key", key);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> new Response(response.getStatusCode().value(),
                        JSON.readTree(response.getBody())));
    }

    private static void assertError(Response response, int status, String code) {
        assertEquals(status, response.status(), response.body().toString());
        assertEquals(code, response.body().get("error").get("code").asString());
    }

    private interface Call {
        Response run(int index) throws Exception;
    }

    private static List<Response> concurrently(int count, Call call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.run(index);
                }));
            }
            start.countDown();
        }
        List<Response> responses = new ArrayList<>();
        for (Future<Response> future : futures) {
            responses.add(future.get());
        }
        return responses;
    }
}
