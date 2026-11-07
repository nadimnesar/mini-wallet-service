package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.common.constant.DbConstants.AddMoneyRequests;
import com.pathaopay.miniwallet.common.constant.DbConstants.IdempotencyKeys;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.DbConstants.LedgerEntries;
import com.pathaopay.miniwallet.common.constant.DbConstants.Limits;
import com.pathaopay.miniwallet.common.constant.DbConstants.Transactions;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.service.WalletService;
import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end asynchronous add-money over HTTP: real controller, idempotency, outbox dispatcher, RabbitMQ (work, retry and
 * dead-letter queues), worker, ledger and PostgreSQL, with the WireMock Visa/Mastercard gateway. POST answers 202 once the
 * request is accepted; the outcome is read from the status endpoint. Gateway scenarios are selected by reserved gateway_ref
 * prefixes. The tests run with two retries (300 ms apart), so a request is tried three times.
 */
class AddMoneyIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private WalletService walletService;

    private RestClient api;
    private String phone;
    private UUID walletId;

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port);
        phone = walletService.createWallet(new CreateWalletRequest(user("u_1001"), "01711000001", WalletType.LIABILITY)).phone();
        walletId = jdbcClient.sql("SELECT wallet_id FROM wallets WHERE phone = :p").param("p", phone)
                .query(UUID.class).single();
    }

    // ---- happy paths ----------------------------------------------------------------------------

    @Test
    void acceptsTheRequestThenCreditsTheWalletAfterVisaConfirmsPayment() {
        Response accepted = addMoney(phone, "key-1", 50000, ref("GW-VISA-1"), "VISA");

        assertEquals(202, accepted.status());
        assertEquals("PENDING", accepted.body().get("status").asString());
        assertEquals(phone, accepted.body().get("phone").asString());
        assertEquals(walletId.toString(), accepted.body().get("wallet_id").asString());
        assertEquals(50000, accepted.body().get("amount").asLong());
        assertEquals(ref("GW-VISA-1"), accepted.body().get("gateway_ref").asString());
        assertEquals("VISA", accepted.body().get("payment_method").asString());
        assertTrue(accepted.body().get("transaction_id").isNull());
        String requestId = accepted.body().get("request_id").asString();
        assertEquals(7, UUID.fromString(requestId).version());
        assertEquals("/api/v1/wallets/add-money/" + requestId, accepted.location());

        JsonNode done = awaitFinal(requestId);

        assertEquals("COMPLETED", done.get("status").asString());
        assertEquals(1, done.get("attempts").asInt());
        assertEquals(50000, done.get("balance").asLong());
        assertTrue(done.get("error_code").isNull());
        UUID transactionId = UUID.fromString(done.get("transaction_id").asString());
        assertEquals(7, transactionId.version());
        assertEquals(1, jdbcClient.sql("SELECT count(*) FROM " + Transactions.TABLE + " WHERE " + Transactions.ID
                        + " = :id AND " + Transactions.TYPE + " = 'ADD_MONEY' AND " + Transactions.GATEWAY_REF
                        + " = :ref AND " + Transactions.PAYMENT_METHOD + " = 'VISA'")
                .param("id", transactionId)
                .param("ref", UUID.fromString(ref("GW-VISA-1"))).query(Long.class).single());
        assertEquals(50000, walletService.getBalance(phone).balance());
        assertEquals(1, gatewayCalls("visa"));
    }

    @Test
    void creditsWalletAfterMastercardConfirmsPaymentAndChainsLedgerEntries() {
        addMoneyAndAwait(phone, "key-1", 50000, ref("GW-1"), "VISA");
        JsonNode second = addMoneyAndAwait(phone, "key-2", 20000, ref("GW-2"), "MASTERCARD");

        assertEquals("COMPLETED", second.get("status").asString());
        assertEquals(70000, second.get("balance").asLong());
        assertEquals("MASTERCARD", second.get("payment_method").asString());
        assertEquals(1, gatewayCalls("mastercard"));
        assertEquals(List.of(1L, 2L), jdbcClient.sql(
                        "SELECT " + LedgerEntries.ENTRY_NO + " FROM " + LedgerEntries.TABLE
                                + " WHERE " + LedgerEntries.WALLET_ID + " = :w ORDER BY " + LedgerEntries.ENTRY_NO)
                .param("w", walletId).query(Long.class).list());
    }

    @Test
    void postsABalancedJournalDebitingTheGatewayClearingAsset() {
        addMoneyAndAwait(phone, "key-1", 50000, ref("GW-1"), "VISA");
        addMoneyAndAwait(phone, "key-2", 20000, ref("GW-2"), "MASTERCARD");

        UUID clearing = AppConstants.GATEWAY_CLEARING_WALLET_ID;
        assertEquals(70000, balanceOf(phone));
        assertEquals(70000, balanceOf(AppConstants.GATEWAY_CLEARING_WALLET_PHONE));
        assertEquals(2, jdbcClient.sql("SELECT count(*) FROM " + LedgerEntries.TABLE + " WHERE "
                        + LedgerEntries.WALLET_ID + " = :w AND " + LedgerEntries.TYPE + " = 'GATEWAY_CLEARING' AND "
                        + LedgerEntries.DIRECTION + " = 'DEBIT' AND " + LedgerEntries.COUNTERPARTY_WALLET_ID + " = :c")
                .param("w", clearing).param("c", walletId).query(Long.class).single());
        assertEquals(0, jdbcClient.sql("SELECT COALESCE(SUM(CASE " + LedgerEntries.DIRECTION
                        + " WHEN 'DEBIT' THEN " + LedgerEntries.AMOUNT + " ELSE -" + LedgerEntries.AMOUNT
                        + " END), 0) FROM " + LedgerEntries.TABLE)
                .query(Long.class).single());
    }

    @Test
    void systemAccountsCannotBeToppedUpAndNoGatewayCallIsMade() {
        assertError(addMoney(AppConstants.REVENUE_WALLET_PHONE, "key-1", 50000, ref("GW-1"), "VISA"),
                422, "SYSTEM_WALLET_NOT_ALLOWED");
        assertError(addMoney(AppConstants.GATEWAY_CLEARING_WALLET_PHONE, "key-2", 50000, ref("GW-2"), "VISA"),
                422, "SYSTEM_WALLET_NOT_ALLOWED");
        assertEquals(0, gatewayCalls("visa"));
        assertEquals(0, ledgerEntries());
        assertEquals(0, addMoneyRequests());
    }

    @Test
    void statusOfAnUnknownRequestIsNotFound() {
        assertError(get("/api/v1/wallets/add-money/" + UUID.randomUUID()), 404, "ADD_MONEY_REQUEST_NOT_FOUND");
        assertError(get("/api/v1/wallets/add-money/not-a-uuid"), 400, "INVALID_ARGUMENT");
    }

    // ---- idempotency -----------------------------------------------------------------------------

    @Test
    void sameKeyAndBodyReturnsTheSameRequestWithoutProcessingAgain() {
        Response first = addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA");
        Response replay = addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA");
        awaitFinal(first.body().get("request_id").asString());
        Response replayAfterCompletion = addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA");

        assertEquals(202, replay.status());
        assertEquals(first.body().get("request_id"), replay.body().get("request_id"));
        assertEquals(202, replayAfterCompletion.status());
        assertEquals(first.body().get("request_id"), replayAfterCompletion.body().get("request_id"));
        assertEquals("COMPLETED", replayAfterCompletion.body().get("status").asString(), "a replay shows the current state");
        assertEquals(1, gatewayCalls("visa"));
        assertEquals(1, ledgerEntries());
        assertEquals(1, addMoneyRequests());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        addMoneyAndAwait(phone, "key-1", 50000, ref("GW-1"), "VISA");
        Response reused = addMoney(phone, "key-1", 60000, ref("GW-1"), "VISA");

        assertError(reused, 422, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(50000, walletService.getBalance(phone).balance());
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        Response response = send(null, body(phone, 50000, ref("GW-1"), "VISA"));

        assertError(response, 400, "MISSING_IDEMPOTENCY_KEY");
        assertEquals(0, gatewayCalls("visa"));
    }

    @Test
    void concurrentRequestsWithSameKeyAreAcceptedOnceAndCreditOnce() throws Exception {
        List<Response> responses = concurrently(20, i -> addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA"));

        Set<JsonNode> requestIds = new HashSet<>();
        for (Response response : responses) {
            assertEquals(202, response.status());
            requestIds.add(response.body().get("request_id"));
        }
        assertEquals(1, requestIds.size(), "every caller gets the same request");
        awaitFinal(requestIds.iterator().next().asString());
        assertEquals(1, gatewayCalls("visa"));
        assertEquals(1, ledgerEntries());
        assertEquals(1, addMoneyRequests());
        assertEquals(50000, walletService.getBalance(phone).balance());
    }

    @Test
    void deterministicRejectionAtAcceptanceIsStoredAndReplayed() {
        Response first = addMoney(phone, "key-1", 99, ref("GW-1"), "VISA");
        Response replay = addMoney(phone, "key-1", 99, ref("GW-1"), "VISA");

        assertError(first, 400, "INVALID_AMOUNT");
        assertEquals(first.status(), replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(1, idempotencyKeys());
        assertEquals(0, addMoneyRequests());
    }

    @Test
    void failedRequestIsReplayedWithItsFailureAndNeverCallsTheGatewayAgain() {
        Response first = addMoney(phone, "key-1", 50000, ref("GW-DECLINED-1"), "VISA");
        JsonNode failed = awaitFinal(first.body().get("request_id").asString());
        Response replay = addMoney(phone, "key-1", 50000, ref("GW-DECLINED-1"), "VISA");

        assertEquals("FAILED", failed.get("status").asString());
        assertEquals(202, replay.status());
        assertEquals(first.body().get("request_id"), replay.body().get("request_id"));
        assertEquals("FAILED", replay.body().get("status").asString());
        assertEquals("PAYMENT_DECLINED", replay.body().get("error_code").asString());
        assertEquals(1, gatewayCalls("visa"), "the replay must not call the gateway again");
    }

    // ---- retries -------------------------------------------------------------------------------------

    @Test
    void transientGatewayFailuresAreRetriedUntilThePaymentIsConfirmed() {
        JsonNode done = addMoneyAndAwait(phone, "key-1", 50000, ref("GW-FLAKY-1"), "VISA");

        assertEquals("COMPLETED", done.get("status").asString());
        assertEquals(ADD_MONEY_MAX_ATTEMPTS, done.get("attempts").asInt(), "two 503s, then the third attempt succeeds");
        assertEquals(50000, done.get("balance").asLong());
        assertEquals(3, gatewayCalls("visa"));
        assertEquals(1, ledgerEntries());
        assertEquals(50000, balanceOf(phone));
        assertEquals(0, deadLetters());
    }

    @Test
    void aRequestWaitingForARetryIsPendingAndShowsTheLastError() {
        Response accepted = addMoney(phone, "key-1", 50000, ref("GW-ERROR-1"), "VISA");
        String requestId = accepted.body().get("request_id").asString();

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).untilAsserted(() -> {
            JsonNode status = get("/api/v1/wallets/add-money/" + requestId).body();
            assertEquals("PENDING", status.get("status").asString());
            assertTrue(status.get("attempts").asInt() >= 1);
            assertEquals("PAYMENT_GATEWAY_ERROR", status.get("error_code").asString());
        });
    }

    @Test
    void requestFailsAndIsDeadLetteredOnceTheRetriesRunOut() {
        Response accepted = addMoney(phone, "key-1", 50000, ref("GW-ERROR-1"), "VISA");
        JsonNode failed = awaitFinal(accepted.body().get("request_id").asString());

        assertEquals("FAILED", failed.get("status").asString());
        assertEquals("PAYMENT_GATEWAY_ERROR", failed.get("error_code").asString());
        assertTrue(failed.get("error_message").asString().endsWith("(gave up after 3 attempts)"));
        assertEquals(ADD_MONEY_MAX_ATTEMPTS, failed.get("attempts").asInt());
        assertEquals(ADD_MONEY_MAX_ATTEMPTS, gatewayCalls("visa"), "tried once and retried twice");
        assertEquals(0, ledgerEntries());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(1, deadLetters()));
    }

    @Test
    void gatewayTimeoutsAreRetriedToo() {
        Response accepted = addMoney(phone, "key-1", 50000, ref("GW-TIMEOUT-1"), "VISA");
        JsonNode failed = awaitFinal(accepted.body().get("request_id").asString());

        assertEquals("FAILED", failed.get("status").asString());
        assertEquals("PAYMENT_GATEWAY_ERROR", failed.get("error_code").asString());
        assertEquals(ADD_MONEY_MAX_ATTEMPTS, gatewayCalls("visa"));
        assertEquals(0, ledgerEntries());
    }

    @Test
    void rejectionsAreFinalAndNeverRetried() {
        JsonNode declined = addMoneyAndAwait(phone, "key-1", 50000, ref("GW-DECLINED-1"), "VISA");

        assertEquals("FAILED", declined.get("status").asString());
        assertEquals(1, declined.get("attempts").asInt());
        assertEquals(1, gatewayCalls("visa"));
        assertEquals(0, deadLetters(), "a business rejection is a normal outcome, not a dead letter");
    }

    @Test
    void aDuplicateDeliveryOfAFinishedRequestDoesNothing() {
        JsonNode done = addMoneyAndAwait(phone, "key-1", 50000, ref("GW-1"), "VISA");

        transactionService.processAddMoney(UUID.fromString(done.get("request_id").asString()));
        transactionService.processAddMoney(UUID.fromString(done.get("request_id").asString()));

        assertEquals(1, gatewayCalls("visa"));
        assertEquals(1, ledgerEntries());
        assertEquals(50000, balanceOf(phone));
    }

    @Test
    void aDeliveryForAnUnknownRequestIsDropped() {
        transactionService.processAddMoney(UUID.randomUUID());

        assertEquals(0, gatewayCalls("visa"));
        assertEquals(0, ledgerEntries());
    }

    // ---- gateway outcomes --------------------------------------------------------------------------

    @Test
    void declinedPaymentDoesNotCredit() {
        JsonNode failed = addMoneyAndAwait(phone, "key-1", 50000, ref("GW-DECLINED-1"), "MASTERCARD");

        assertFailed(failed, "PAYMENT_DECLINED");
        assertTrue(failed.get("error_message").asString().contains("INSUFFICIENT_FUNDS"));
        assertTrue(failed.get("transaction_id").isNull());
        assertTrue(failed.get("balance").isNull());
        assertEquals(0, ledgerEntries());
    }

    @Test
    void paymentThatStaysPendingIsRetriedThenFailsWithoutCrediting() {
        JsonNode failed = addMoneyAndAwait(phone, "key-1", 50000, ref("GW-PENDING-1"), "VISA");

        assertFailed(failed, "PAYMENT_PENDING");
        assertEquals(ADD_MONEY_MAX_ATTEMPTS, failed.get("attempts").asInt());
        assertEquals(ADD_MONEY_MAX_ATTEMPTS, gatewayCalls("visa"));
        assertTrue(failed.get("error_message").asString().contains("AWAITING_3DS_AUTHENTICATION"));
        assertEquals(0, ledgerEntries());
    }

    @Test
    void paymentCapturedAfterThreeDSecureCompletesIsCreditedOnALaterAttempt() {
        JsonNode done = addMoneyAndAwait(phone, "key-1", 50000, ref("GW-SLOW3DS-1"), "VISA");

        assertEquals("COMPLETED", done.get("status").asString());
        assertEquals(3, done.get("attempts").asInt(), "pending twice, captured on the third check");
        assertEquals(50000, done.get("balance").asLong());
        assertEquals(3, gatewayCalls("visa"));
        assertEquals(1, ledgerEntries());
        assertEquals(0, deadLetters());
    }

    @Test
    void unknownPaymentIsRejected() {
        assertFailed(addMoneyAndAwait(phone, "key-1", 50000, ref("GW-NOTFOUND-1"), "VISA"), "PAYMENT_NOT_FOUND");
    }

    @Test
    void paymentCapturedForDifferentAmountIsRejected() {
        assertFailed(addMoneyAndAwait(phone, "key-1", 50000, ref("GW-MISMATCH-1"), "VISA"), "PAYMENT_MISMATCH");
        assertEquals(0, ledgerEntries());
    }

    @Test
    void paymentCapturedForAnotherWalletIsRejected() {
        assertFailed(addMoneyAndAwait(phone, "key-1", 50000, ref("GW-OTHERWALLET-1"), "VISA"), "PAYMENT_MISMATCH");
        assertEquals(0, ledgerEntries());
        assertEquals(0, balanceOf(phone));
    }

    @Test
    void gatewayIsToldWhichWalletThePaymentIsFor() {
        addMoneyAndAwait("+8801711000001", "key-1", 50000, ref("GW-REF-1"), "VISA");

        String found = RestClient.create(paymentGatewayUrl()).post()
                .uri("/__admin/requests/find")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"method": "POST", "url": "/visa/v1/payments/verify"}""")
                .retrieve().body(String.class);
        JsonNode requests = JSON.readTree(found).get("requests");
        assertEquals(1, requests.size());
        assertEquals(phone, JSON.readTree(requests.get(0).get("body").asString()).get("merchant_reference").asString());
    }

    // ---- business rules ----------------------------------------------------------------------------

    @Test
    void gatewayRefCanCreditOnlyOnce() {
        addMoneyAndAwait(phone, "key-1", 50000, ref("GW-1"), "VISA");
        Response duplicate = addMoney(phone, "key-2", 50000, ref("GW-1"), "VISA");

        assertError(duplicate, 409, "DUPLICATE_GATEWAY_REF");
        assertEquals(1, gatewayCalls("visa"), "a known gateway_ref is rejected before calling the gateway");
        assertEquals(50000, walletService.getBalance(phone).balance());
    }

    @Test
    void aGatewayRefAlreadyAwaitingCreditIsRejectedAtOnce() {
        Response first = addMoney(phone, "key-1", 50000, ref("GW-ERROR-1"), "VISA");
        Response second = addMoney(phone, "key-2", 50000, ref("GW-ERROR-1"), "VISA");

        assertEquals(202, first.status());
        assertError(second, 409, "DUPLICATE_GATEWAY_REF");
        awaitFinal(first.body().get("request_id").asString());
        assertEquals(1, addMoneyRequests());
    }

    @Test
    void aFailedRequestFreesItsGatewayRef() {
        awaitFinal(addMoney(phone, "key-1", 50000, ref("GW-DECLINED-1"), "VISA").body().get("request_id").asString());

        Response again = addMoney(phone, "key-2", 50000, ref("GW-DECLINED-1"), "VISA");

        assertEquals(202, again.status());
        assertEquals(2, awaitRequests(2));
    }

    @Test
    void concurrentRequestsWithSameGatewayRefCreditOnce() throws Exception {
        List<Response> responses = concurrently(10, i -> addMoney(phone, "key-" + i, 50000, ref("GW-1"), "VISA"));

        List<Response> accepted = responses.stream().filter(r -> r.status() == 202).toList();
        assertEquals(1, accepted.size());
        responses.stream().filter(r -> r.status() != 202)
                .forEach(r -> assertError(r, 409, "DUPLICATE_GATEWAY_REF"));
        awaitFinal(accepted.getFirst().body().get("request_id").asString());
        assertEquals(50000, walletService.getBalance(phone).balance());
    }

    @Test
    void concurrentCreditsToOneWalletAreAllApplied() throws Exception {
        List<Response> responses = concurrently(10, i -> addMoney(phone, "key-" + i, 1000 * (i + 1), ref("GW-" + i),
                i % 2 == 0 ? "VISA" : "MASTERCARD"));

        for (Response response : responses) {
            assertEquals(202, response.status());
            assertEquals("COMPLETED", awaitFinal(response.body().get("request_id").asString()).get("status").asString());
        }
        assertEquals(55000, walletService.getBalance(phone).balance());
        assertEquals(10, ledgerEntries());
    }

    @Test
    void unknownWalletIsRejectedWithoutCallingGateway() {
        assertError(addMoney("01999999999", "key-1", 50000, ref("GW-1"), "VISA"), 404, "WALLET_NOT_FOUND");
        assertEquals(0, gatewayCalls("visa"));
        assertEquals(0, addMoneyRequests());
    }

    @Test
    void phoneIsRequiredInTheBody() {
        Response response = send("key-1", """
                {"amount": 50000, "gateway_ref": "%s", "payment_method": "VISA"}""".formatted(ref("GW-1")));

        assertError(response, 400, "VALIDATION_ERROR");
        assertEquals("phone: phone is required", response.body().get("error").get("message").asString());
        assertEquals(0, gatewayCalls("visa"));
    }

    @Test
    void phoneMustBeAValidBangladeshiNumber() {
        Response response = send("key-1", """
                {"phone": "w_123", "amount": 50000, "gateway_ref": "%s", "payment_method": "VISA"}"""
                .formatted(ref("GW-1")));

        assertError(response, 400, "VALIDATION_ERROR");
        assertEquals(0, gatewayCalls("visa"));
    }

    @Test
    void phoneInAnyAcceptedFormatCreditsTheSameWallet() {
        assertEquals("COMPLETED", addMoneyAndAwait("+8801711000001", "key-1", 50000, ref("GW-1"), "VISA").get("status").asString());
        assertEquals("COMPLETED", addMoneyAndAwait("1711000001", "key-2", 10000, ref("GW-2"), "VISA").get("status").asString());
        assertEquals(60000, balanceOf(phone));
    }

    @Test
    void amountOutsideLimitsIsRejectedBeforeAnythingIsQueued() {
        Response tooSmall = addMoney(phone, "key-1", 99, ref("GW-1"), "VISA");
        Response tooLarge = addMoney(phone, "key-2", 2_500_001, ref("GW-2"), "VISA");

        assertError(tooSmall, 400, "INVALID_AMOUNT");
        assertEquals("amount must be at least 100 poisha (1 BDT)", tooSmall.body().get("error").get("message").asString());
        assertError(tooLarge, 400, "INVALID_AMOUNT");
        assertEquals("amount must be at most 2500000 poisha (25000 BDT)", tooLarge.body().get("error").get("message").asString());
        assertEquals(0, gatewayCalls("visa"), "an amount outside the limits is rejected before calling the gateway");
        assertEquals(0, addMoneyRequests());
        assertEquals(100, addMoneyAndAwait(phone, "key-3", 100, ref("GW-3"), "VISA").get("amount").asLong());
        assertEquals("COMPLETED", addMoneyAndAwait(phone, "key-4", 2_500_000, ref("GW-4"), "VISA").get("status").asString());
    }

    @Test
    void limitsAreReadFromTheLimitsTable() {
        setLimit("ADD_MONEY", 500, 60000);
        try {
            assertError(addMoney(phone, "key-1", 499, ref("GW-1"), "VISA"), 400, "INVALID_AMOUNT");
            assertError(addMoney(phone, "key-2", 60001, ref("GW-2"), "VISA"), 400, "INVALID_AMOUNT");
            assertEquals("COMPLETED", addMoneyAndAwait(phone, "key-3", 60000, ref("GW-3"), "VISA").get("status").asString());
        } finally {
            setLimit("ADD_MONEY", 100, 2_500_000);
        }
    }

    @Test
    void anAcceptedRequestIsCreditedEvenIfTheLimitsChangeBeforeItIsProcessed() {
        // Two requests accepted under the default limits (stored directly and marked published, so the dispatcher
        // leaves them alone and the test controls when they are processed). The customer has paid both.
        UUID belowNewMin = acceptedRequest(50000, ref("GW-1"));
        UUID aboveNewMax = acceptedRequest(70000, ref("GW-2"));
        setLimit("ADD_MONEY", 60000, 60000);
        try {
            transactionService.processAddMoney(belowNewMin);
            transactionService.processAddMoney(aboveNewMax);
        } finally {
            setLimit("ADD_MONEY", 100, 2_500_000);
        }

        for (UUID requestId : List.of(belowNewMin, aboveNewMax)) {
            JsonNode status = get("/api/v1/wallets/add-money/" + requestId).body();
            assertEquals("COMPLETED", status.get("status").asString(), status.toString());
        }
        assertEquals(120000, balanceOf(phone));
        assertEquals(2, ledgerEntries());
    }

    @Test
    void noRowLockIsHeldWhileTheGatewayAnswers() {
        UUID requestId = UUID.fromString(addMoney(phone, "key-1", 50000, ref("GW-TIMEOUT-1"), "VISA")
                .body().get("request_id").asString());
        // The TIMEOUT stub answers after 10 s and the client gives up after 1 s: once the gateway has seen the call,
        // the worker is waiting for the answer.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(10)).until(() -> gatewayCalls("visa") >= 1);

        // NOWAIT fails at once if the worker still holds the request row's lock.
        UUID locked = jdbcClient.sql("SELECT " + AddMoneyRequests.ID + " FROM " + AddMoneyRequests.TABLE
                        + " WHERE " + AddMoneyRequests.ID + " = :id FOR UPDATE NOWAIT")
                .param("id", requestId).query(UUID.class).single();

        assertEquals(requestId, locked);
    }

    @Test
    void aLockTimeoutInTheWorkerDoesNotUseUpAnAttempt() throws Exception {
        String requestId;
        // Hold the clearing row for longer than one lock timeout, and across more timeouts than there are attempts.
        try (AutoCloseable lock = holdRowLock(AppConstants.GATEWAY_CLEARING_WALLET_ID)) {
            requestId = addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA").body().get("request_id").asString();
            Thread.sleep(2000L * (ADD_MONEY_MAX_ATTEMPTS + 1) + 1000);
        }

        JsonNode done = awaitFinal(requestId);

        assertEquals("COMPLETED", done.get("status").asString());
        assertEquals(1, done.get("attempts").asInt());
        assertEquals(50000, balanceOf(phone));
    }

    @Test
    void aRequestThatAlwaysCrashesTheWorkerIsCountedAndEndsFailedOnTheDeadLetterQueue() {
        jdbcClient.sql("CREATE FUNCTION test_reject_transactions() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'simulated bug'; END; $$").update();
        jdbcClient.sql("CREATE TRIGGER test_reject_transactions BEFORE INSERT ON " + Transactions.TABLE
                + " FOR EACH ROW EXECUTE FUNCTION test_reject_transactions()").update();
        try {
            JsonNode failed = addMoneyAndAwait(phone, "key-1", 50000, ref("GW-1"), "VISA");

            assertEquals("FAILED", failed.get("status").asString());
            assertEquals("INTERNAL_SERVER_ERROR", failed.get("error_code").asString());
            assertTrue(failed.get("error_message").asString().endsWith("(gave up after 3 attempts)"));
            assertEquals(ADD_MONEY_MAX_ATTEMPTS, failed.get("attempts").asInt());
            assertEquals(0, ledgerEntries());
            assertEquals(0, balanceOf(phone));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(1, deadLetters()));
        } finally {
            jdbcClient.sql("DROP TRIGGER test_reject_transactions ON " + Transactions.TABLE).update();
            jdbcClient.sql("DROP FUNCTION test_reject_transactions()").update();
        }
    }

    @Test
    void aMessageThatComesBackBeforeTheBackoffEndedIsParkedAgainWithoutCountingAnAttempt() {
        // One failed attempt was recorded just now (3 s in the future), so its backoff has not elapsed.
        UUID requestId = retryingRequest(50000, ref("GW-1"), 3);

        transactionService.processAddMoney(requestId);

        JsonNode early = get("/api/v1/wallets/add-money/" + requestId).body();
        assertEquals("PENDING", early.get("status").asString());
        assertEquals(1, early.get("attempts").asInt(), "an early redelivery is not an attempt");
        assertEquals(0, gatewayCalls("visa"), "and it does not even call the gateway");

        JsonNode done = awaitFinal(requestId.toString());
        assertEquals("COMPLETED", done.get("status").asString());
        assertEquals(2, done.get("attempts").asInt());
        assertEquals(1, gatewayCalls("visa"), "the parked message waited, then verified the payment once");
        assertEquals(50000, balanceOf(phone));
    }

    /** A PENDING request with one recorded failed attempt whose backoff ends {@code backoffSeconds} from now. */
    private UUID retryingRequest(long amount, String gatewayRef, int backoffSeconds) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO " + AddMoneyRequests.TABLE + " (" + String.join(", ", AddMoneyRequests.ID,
                        AddMoneyRequests.WALLET_ID, AddMoneyRequests.AMOUNT, AddMoneyRequests.GATEWAY_REF,
                        AddMoneyRequests.PAYMENT_METHOD, AddMoneyRequests.ATTEMPTS, AddMoneyRequests.PUBLISHED_AT,
                        AddMoneyRequests.UPDATED_AT) + ")"
                        + " VALUES (:id, :walletId, :amount, CAST(:ref AS UUID), 'VISA', 1, now(),"
                        + " now() + :seconds * interval '1 second')")
                .param("id", id).param("walletId", walletId).param("amount", amount).param("ref", gatewayRef)
                .param("seconds", backoffSeconds).update();
        return id;
    }

    /** Stores a PENDING request as acceptance would, with published_at set so the dispatcher does not pick it up. */
    private UUID acceptedRequest(long amount, String gatewayRef) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO " + AddMoneyRequests.TABLE + " (" + String.join(", ", AddMoneyRequests.ID,
                        AddMoneyRequests.WALLET_ID, AddMoneyRequests.AMOUNT, AddMoneyRequests.GATEWAY_REF,
                        AddMoneyRequests.PAYMENT_METHOD, AddMoneyRequests.PUBLISHED_AT) + ")"
                        + " VALUES (:id, :walletId, :amount, CAST(:ref AS UUID), 'VISA', now())")
                .param("id", id).param("walletId", walletId).param("amount", amount).param("ref", gatewayRef)
                .update();
        return id;
    }

    private void setLimit(String type, long min, long max) {
        jdbcClient.sql("UPDATE " + Limits.TABLE + " SET " + Limits.MIN_AMOUNT + " = :min, " + Limits.MAX_AMOUNT
                + " = :max WHERE " + Limits.TRANSACTION_TYPE + " = :type")
                .param("min", min).param("max", max).param("type", type).update();
    }

    @Test
    void amountMustBeAnExactInteger() {
        for (String amount : List.of("10000.9", "10000.0", "\"10000\"", "1e4")) {
            Response response = send("key-" + amount, """
                    {"phone": "%s", "amount": %s, "gateway_ref": "%s", "payment_method": "VISA"}"""
                    .formatted(phone, amount, ref("GW-" + amount)));
            assertError(response, 400, "INVALID_PAYLOAD");
        }
        assertEquals(0, gatewayCalls("visa"));
        assertEquals(0, balanceOf(phone));
    }

    @Test
    void unsupportedPaymentMethodIsRejected() {
        Response response = addMoney(phone, "key-1", 50000, ref("GW-1"), "AMEX");

        assertError(response, 400, "INVALID_PAYLOAD");
        assertEquals("Invalid value for field: payment_method", response.body().get("error").get("message").asString());
    }

    @Test
    void gatewayRefMustBeAUuid() {
        Response response = addMoney(phone, "key-1", 50000, "GW-20261105-9981", "VISA");

        assertError(response, 400, "INVALID_PAYLOAD");
        assertEquals("Invalid value for field: gateway_ref", response.body().get("error").get("message").asString());
        assertEquals(0, gatewayCalls("visa"));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private record Response(int status, JsonNode body, String location) {
    }

    private Response addMoney(String phone, String key, long amount, String gatewayRef, String paymentMethod) {
        return send(key, body(phone, amount, gatewayRef, paymentMethod));
    }

    /** Accepts the request (must be 202) and waits for the worker to finish it: the final status. */
    private JsonNode addMoneyAndAwait(String phone, String key, long amount, String gatewayRef, String paymentMethod) {
        Response accepted = addMoney(phone, key, amount, gatewayRef, paymentMethod);
        assertEquals(202, accepted.status(), accepted.body().toString());
        return awaitFinal(accepted.body().get("request_id").asString());
    }

    /** Polls the status endpoint until the request is COMPLETED or FAILED. */
    private JsonNode awaitFinal(String requestId) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until(() -> {
            Response response = get("/api/v1/wallets/add-money/" + requestId);
            assertEquals(200, response.status(), response.body().toString());
            last[0] = response.body();
            return !"PENDING".equals(last[0].get("status").asString());
        });
        return last[0];
    }

    private Response get(String path) {
        return api.get().uri(path).exchange((request, response) -> new Response(response.getStatusCode().value(),
                JSON.readTree(response.getBody()), null));
    }

    private static void assertFailed(JsonNode request, String errorCode) {
        assertEquals("FAILED", request.get("status").asString(), request.toString());
        assertEquals(errorCode, request.get("error_code").asString());
    }

    private int awaitRequests(int count) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until(() ->
                jdbcClient.sql("SELECT count(*) FROM " + AddMoneyRequests.TABLE + " WHERE " + AddMoneyRequests.STATUS
                        + " <> 'PENDING'").query(Long.class).single() == count);
        return (int) addMoneyRequests();
    }

    private static String body(String phone, long amount, String gatewayRef, String paymentMethod) {
        return """
                {"phone": "%s", "amount": %d, "gateway_ref": "%s", "payment_method": "%s"}"""
                .formatted(phone, amount, gatewayRef, paymentMethod);
    }

    private Response send(String key, String body) {
        return api.post()
                .uri("/api/v1/wallets/add-money")
                .headers(h -> {
                    if (key != null) {
                        h.set("Idempotency-Key", key);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> new Response(response.getStatusCode().value(),
                        JSON.readTree(response.getBody()), response.getHeaders().getFirst(HttpHeaders.LOCATION)));
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

    private static long gatewayCalls(String network) {
        String count = RestClient.create(paymentGatewayUrl()).post()
                .uri("/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"method": "POST", "url": "/%s/v1/payments/verify"}""".formatted(network))
                .retrieve()
                .body(String.class);
        return JSON.readTree(count).get("count").asLong();
    }

    /** Number of credited top-ups: each one is a journal of an ADD_MONEY leg and a GATEWAY_CLEARING leg. */
    private long ledgerEntries() {
        return jdbcClient.sql("SELECT count(*) FROM " + LedgerEntries.TABLE + " WHERE "
                + LedgerEntries.TYPE + " = 'ADD_MONEY'").query(Long.class).single();
    }

    private long balanceOf(String phone) {
        return walletService.getBalance(phone).balance();
    }

    private long addMoneyRequests() {
        return jdbcClient.sql("SELECT count(*) FROM " + AddMoneyRequests.TABLE).query(Long.class).single();
    }

    private long idempotencyKeys() {
        return jdbcClient.sql("SELECT count(*) FROM " + IdempotencyKeys.TABLE).query(Long.class).single();
    }

    /** Deterministic user_id (a UUID) for a readable test name. */
    private static UUID user(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** Mock-gateway scenario prefixes (see docker/wiremock): the first UUID group picks the outcome. */
    private static final Map<String, String> SCENARIO_PREFIXES = Map.of(
            "DECLINED", "00000402", "PENDING", "00000202", "NOTFOUND", "00000404",
            "MISMATCH", "00000422", "OTHERWALLET", "00000423", "ERROR", "00000500", "FLAKY", "00000503", "SLOW3DS", "00000203",
            "TIMEOUT", "00000504");

    /**
     * Deterministic gateway_ref (a UUID) for a readable test name. A name containing a scenario
     * keyword (e.g. GW-DECLINED-1) gets that scenario's reserved prefix; anything else is captured.
     */
    private static String ref(String name) {
        String uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
        return SCENARIO_PREFIXES.entrySet().stream()
                .filter(scenario -> name.contains(scenario.getKey()))
                .map(scenario -> scenario.getValue() + uuid.substring(8))
                .findFirst()
                .orElse(uuid);
    }
}
