package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.common.constant.DbConstants.IdempotencyKeys;
import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.DbConstants.LedgerEntries;
import com.pathaopay.miniwallet.common.constant.DbConstants.Limits;
import com.pathaopay.miniwallet.common.constant.DbConstants.Transactions;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
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

import java.nio.charset.StandardCharsets;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end add-money over HTTP: real controller, idempotency, ledger and PostgreSQL, with the
 * WireMock Visa/Mastercard gateway. Gateway scenarios are selected by reserved gateway_ref prefixes.
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
        phone = walletService.createWallet(new CreateWalletRequest(user("u_1001"), "01711000001")).phone();
        walletId = jdbcClient.sql("SELECT wallet_id FROM wallets WHERE phone = :p").param("p", phone)
                .query(UUID.class).single();
    }

    // ---- happy paths ----------------------------------------------------------------------------

    @Test
    void creditsWalletAfterVisaConfirmsPayment() {
        Response response = addMoney(phone, "key-1", 50000, ref("GW-VISA-1"), "VISA");

        assertEquals(201, response.status());
        assertEquals(phone, response.body().get("phone").asString());
        assertEquals(walletId.toString(), response.body().get("wallet_id").asString());
        assertEquals(50000, response.body().get("added_amount").asLong());
        assertEquals(50000, response.body().get("balance").asLong());
        assertEquals(ref("GW-VISA-1"), response.body().get("gateway_ref").asString());
        assertEquals("VISA", response.body().get("payment_method").asString());
        assertEquals(7, UUID.fromString(response.body().get("transaction_id").asString()).version());
        assertEquals(1, jdbcClient.sql("SELECT count(*) FROM " + Transactions.TABLE + " WHERE " + Transactions.ID
                        + " = :id AND " + Transactions.TYPE + " = 'ADD_MONEY' AND " + Transactions.GATEWAY_REF
                        + " = :ref AND " + Transactions.PAYMENT_METHOD + " = 'VISA'")
                .param("id", UUID.fromString(response.body().get("transaction_id").asString()))
                .param("ref", UUID.fromString(ref("GW-VISA-1"))).query(Long.class).single());
        assertEquals(50000, walletService.getBalance(phone).balance());
        assertEquals(1, gatewayCalls("visa"));
    }

    @Test
    void creditsWalletAfterMastercardConfirmsPaymentAndChainsLedgerEntries() {
        addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA");
        Response second = addMoney(phone, "key-2", 20000, ref("GW-2"), "MASTERCARD");

        assertEquals(201, second.status());
        assertEquals(70000, second.body().get("balance").asLong());
        assertEquals("MASTERCARD", second.body().get("payment_method").asString());
        assertEquals(1, gatewayCalls("mastercard"));
        assertEquals(List.of(1L, 2L), jdbcClient.sql(
                        "SELECT " + LedgerEntries.ENTRY_NO + " FROM " + LedgerEntries.TABLE
                                + " WHERE " + LedgerEntries.WALLET_ID + " = :w ORDER BY " + LedgerEntries.ENTRY_NO)
                .param("w", walletId).query(Long.class).list());
    }

    @Test
    void postsABalancedJournalDebitingTheGatewayClearingAsset() {
        addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA");
        addMoney(phone, "key-2", 20000, ref("GW-2"), "MASTERCARD");

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
    }

    // ---- idempotency -----------------------------------------------------------------------------

    @Test
    void sameKeyAndBodyReplaysOriginalResponseWithoutProcessingAgain() {
        Response first = addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA");
        Response replay = addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA");

        assertEquals(201, replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(1, gatewayCalls("visa"));
        assertEquals(1, ledgerEntries());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA");
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
    void concurrentRequestsWithSameKeyExecuteExactlyOnce() throws Exception {
        List<Response> responses = concurrently(20, i -> addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA"));

        Set<JsonNode> bodies = new HashSet<>();
        for (Response response : responses) {
            assertEquals(201, response.status());
            bodies.add(response.body());
        }
        assertEquals(1, bodies.size(), "every caller gets the same response");
        assertEquals(1, gatewayCalls("visa"));
        assertEquals(1, ledgerEntries());
        assertEquals(50000, walletService.getBalance(phone).balance());
    }

    @Test
    void deterministicFailureIsStoredAndReplayed() {
        Response first = addMoney(phone, "key-1", 50000, ref("GW-DECLINED-1"), "VISA");
        Response replay = addMoney(phone, "key-1", 50000, ref("GW-DECLINED-1"), "VISA");

        assertError(first, 402, "PAYMENT_DECLINED");
        assertEquals(first.status(), replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(1, gatewayCalls("visa"), "the replay must not call the gateway again");
    }

    @Test
    void transientGatewayFailureIsNotStoredSoTheSameKeyCanBeRetried() {
        Response first = addMoney(phone, "key-1", 50000, ref("GW-ERROR-1"), "VISA");
        Response retry = addMoney(phone, "key-1", 50000, ref("GW-ERROR-1"), "VISA");

        assertError(first, 502, "PAYMENT_GATEWAY_ERROR");
        assertError(retry, 502, "PAYMENT_GATEWAY_ERROR");
        assertEquals(2, gatewayCalls("visa"), "the retry is executed again");
        assertEquals(0, idempotencyKeys());
    }

    // ---- gateway outcomes --------------------------------------------------------------------------

    @Test
    void declinedPaymentDoesNotCredit() {
        Response response = addMoney(phone, "key-1", 50000, ref("GW-DECLINED-1"), "MASTERCARD");

        assertError(response, 402, "PAYMENT_DECLINED");
        assertTrue(response.body().get("error").get("message").asString().contains("INSUFFICIENT_FUNDS"));
        assertEquals(0, ledgerEntries());
    }

    @Test
    void pendingPaymentDoesNotCredit() {
        assertError(addMoney(phone, "key-1", 50000, ref("GW-PENDING-1"), "VISA"), 402, "PAYMENT_DECLINED");
        assertEquals(0, ledgerEntries());
    }

    @Test
    void unknownPaymentIsRejected() {
        assertError(addMoney(phone, "key-1", 50000, ref("GW-NOTFOUND-1"), "VISA"), 422, "PAYMENT_NOT_FOUND");
    }

    @Test
    void paymentCapturedForDifferentAmountIsRejected() {
        assertError(addMoney(phone, "key-1", 50000, ref("GW-MISMATCH-1"), "VISA"), 422, "PAYMENT_MISMATCH");
        assertEquals(0, ledgerEntries());
    }

    @Test
    void paymentCapturedForAnotherWalletIsRejected() {
        assertError(addMoney(phone, "key-1", 50000, ref("GW-OTHERWALLET-1"), "VISA"), 422, "PAYMENT_MISMATCH");
        assertEquals(0, ledgerEntries());
        assertEquals(0, balanceOf(phone));
    }

    @Test
    void gatewayIsToldWhichWalletThePaymentIsFor() {
        addMoney("+8801711000001", "key-1", 50000, ref("GW-REF-1"), "VISA");

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

    @Test
    void gatewayTimeoutIsReportedAsGatewayError() {
        assertError(addMoney(phone, "key-1", 50000, ref("GW-TIMEOUT-1"), "VISA"), 502, "PAYMENT_GATEWAY_ERROR");
        assertEquals(0, ledgerEntries());
    }

    // ---- business rules ----------------------------------------------------------------------------

    @Test
    void gatewayRefCanCreditOnlyOnce() {
        addMoney(phone, "key-1", 50000, ref("GW-1"), "VISA");
        Response duplicate = addMoney(phone, "key-2", 50000, ref("GW-1"), "VISA");

        assertError(duplicate, 409, "DUPLICATE_GATEWAY_REF");
        assertEquals(1, gatewayCalls("visa"), "a known gateway_ref is rejected before calling the gateway");
        assertEquals(50000, walletService.getBalance(phone).balance());
    }

    @Test
    void concurrentRequestsWithSameGatewayRefCreditOnce() throws Exception {
        List<Response> responses = concurrently(10, i -> addMoney(phone, "key-" + i, 50000, ref("GW-1"), "VISA"));

        assertEquals(1, responses.stream().filter(r -> r.status() == 201).count());
        responses.stream().filter(r -> r.status() != 201)
                .forEach(r -> assertError(r, 409, "DUPLICATE_GATEWAY_REF"));
        assertEquals(50000, walletService.getBalance(phone).balance());
    }

    @Test
    void concurrentCreditsToOneWalletAreAllApplied() throws Exception {
        List<Response> responses = concurrently(10, i -> addMoney(phone, "key-" + i, 1000 * (i + 1), ref("GW-" + i),
                i % 2 == 0 ? "VISA" : "MASTERCARD"));

        responses.forEach(r -> assertEquals(201, r.status()));
        assertEquals(55000, walletService.getBalance(phone).balance());
        assertEquals(10, ledgerEntries());
    }

    @Test
    void unknownWalletIsRejectedWithoutCallingGateway() {
        assertError(addMoney("01999999999", "key-1", 50000, ref("GW-1"), "VISA"), 404, "WALLET_NOT_FOUND");
        assertEquals(0, gatewayCalls("visa"));
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
        assertEquals(201, addMoney("+8801711000001", "key-1", 50000, ref("GW-1"), "VISA").status());
        assertEquals(201, addMoney("1711000001", "key-2", 10000, ref("GW-2"), "VISA").status());
        assertEquals(60000, balanceOf(phone));
    }

    @Test
    void amountOutsideLimitsIsRejected() {
        Response tooSmall = addMoney(phone, "key-1", 99, ref("GW-1"), "VISA");
        Response tooLarge = addMoney(phone, "key-2", 2_500_001, ref("GW-2"), "VISA");

        assertError(tooSmall, 400, "INVALID_AMOUNT");
        assertEquals("amount must be at least 100 poisha (1 BDT)", tooSmall.body().get("error").get("message").asString());
        assertError(tooLarge, 400, "INVALID_AMOUNT");
        assertEquals("amount must be at most 2500000 poisha (25000 BDT)", tooLarge.body().get("error").get("message").asString());
        assertEquals(0, gatewayCalls("visa"), "an amount outside the limits is rejected before calling the gateway");
        assertEquals(100, addMoney(phone, "key-3", 100, ref("GW-3"), "VISA").body().get("added_amount").asLong());
        assertEquals(201, addMoney(phone, "key-4", 2_500_000, ref("GW-4"), "VISA").status());
    }

    @Test
    void limitsAreReadFromTheLimitsTable() {
        setLimit("ADD_MONEY", 500, 60000);
        try {
            assertError(addMoney(phone, "key-1", 499, ref("GW-1"), "VISA"), 400, "INVALID_AMOUNT");
            assertError(addMoney(phone, "key-2", 60001, ref("GW-2"), "VISA"), 400, "INVALID_AMOUNT");
            assertEquals(201, addMoney(phone, "key-3", 60000, ref("GW-3"), "VISA").status());
        } finally {
            setLimit("ADD_MONEY", 100, 2_500_000);
        }
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

    private record Response(int status, JsonNode body) {
    }

    private Response addMoney(String phone, String key, long amount, String gatewayRef, String paymentMethod) {
        return send(key, body(phone, amount, gatewayRef, paymentMethod));
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
            "MISMATCH", "00000422", "OTHERWALLET", "00000423", "ERROR", "00000500", "TIMEOUT", "00000504");

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
