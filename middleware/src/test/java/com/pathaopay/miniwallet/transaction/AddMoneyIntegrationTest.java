package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.dto.CreateWalletRequest;
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
    private UUID walletId;

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port);
        walletId = walletService.createWallet(new CreateWalletRequest(user("u_1001"), "01711000001")).walletId();
    }

    // ---- happy paths ----------------------------------------------------------------------------

    @Test
    void creditsWalletAfterVisaConfirmsPayment() {
        Response response = addMoney(walletId, "key-1", 50000, ref("GW-VISA-1"), "VISA");

        assertEquals(201, response.status());
        assertEquals(walletId.toString(), response.body().get("wallet_id").asString());
        assertEquals(50000, response.body().get("added_amount").asLong());
        assertEquals(50000, response.body().get("balance").asLong());
        assertEquals(ref("GW-VISA-1"), response.body().get("gateway_ref").asString());
        assertEquals("VISA", response.body().get("payment_method").asString());
        assertEquals(7, UUID.fromString(response.body().get("transaction_id").asString()).version());
        assertEquals(50000, walletService.getWallet(walletId).balance());
        assertEquals(1, gatewayCalls("visa"));
    }

    @Test
    void creditsWalletAfterMastercardConfirmsPaymentAndChainsLedgerEntries() {
        addMoney(walletId, "key-1", 50000, ref("GW-1"), "VISA");
        Response second = addMoney(walletId, "key-2", 20000, ref("GW-2"), "MASTERCARD");

        assertEquals(201, second.status());
        assertEquals(70000, second.body().get("balance").asLong());
        assertEquals("MASTERCARD", second.body().get("payment_method").asString());
        assertEquals(1, gatewayCalls("mastercard"));
        assertEquals(List.of(1L, 2L), jdbcClient.sql(
                        "SELECT entry_no FROM wallet_transactions WHERE wallet_id = :w ORDER BY entry_no")
                .param("w", walletId).query(Long.class).list());
    }

    // ---- idempotency -----------------------------------------------------------------------------

    @Test
    void sameKeyAndBodyReplaysOriginalResponseWithoutProcessingAgain() {
        Response first = addMoney(walletId, "key-1", 50000, ref("GW-1"), "VISA");
        Response replay = addMoney(walletId, "key-1", 50000, ref("GW-1"), "VISA");

        assertEquals(201, replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(1, gatewayCalls("visa"));
        assertEquals(1, ledgerEntries());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        addMoney(walletId, "key-1", 50000, ref("GW-1"), "VISA");
        Response reused = addMoney(walletId, "key-1", 60000, ref("GW-1"), "VISA");

        assertError(reused, 422, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(50000, walletService.getWallet(walletId).balance());
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        Response response = send(null, body(walletId, 50000, ref("GW-1"), "VISA"));

        assertError(response, 400, "MISSING_IDEMPOTENCY_KEY");
        assertEquals(0, gatewayCalls("visa"));
    }

    @Test
    void concurrentRequestsWithSameKeyExecuteExactlyOnce() throws Exception {
        List<Response> responses = concurrently(20, i -> addMoney(walletId, "key-1", 50000, ref("GW-1"), "VISA"));

        Set<JsonNode> bodies = new HashSet<>();
        for (Response response : responses) {
            assertEquals(201, response.status());
            bodies.add(response.body());
        }
        assertEquals(1, bodies.size(), "every caller gets the same response");
        assertEquals(1, gatewayCalls("visa"));
        assertEquals(1, ledgerEntries());
        assertEquals(50000, walletService.getWallet(walletId).balance());
    }

    @Test
    void deterministicFailureIsStoredAndReplayed() {
        Response first = addMoney(walletId, "key-1", 50000, ref("GW-DECLINED-1"), "VISA");
        Response replay = addMoney(walletId, "key-1", 50000, ref("GW-DECLINED-1"), "VISA");

        assertError(first, 402, "PAYMENT_DECLINED");
        assertEquals(first.status(), replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(1, gatewayCalls("visa"), "the replay must not call the gateway again");
    }

    @Test
    void transientGatewayFailureIsNotStoredSoTheSameKeyCanBeRetried() {
        Response first = addMoney(walletId, "key-1", 50000, ref("GW-ERROR-1"), "VISA");
        Response retry = addMoney(walletId, "key-1", 50000, ref("GW-ERROR-1"), "VISA");

        assertError(first, 502, "PAYMENT_GATEWAY_ERROR");
        assertError(retry, 502, "PAYMENT_GATEWAY_ERROR");
        assertEquals(2, gatewayCalls("visa"), "the retry is executed again");
        assertEquals(0, idempotencyKeys());
    }

    // ---- gateway outcomes --------------------------------------------------------------------------

    @Test
    void declinedPaymentDoesNotCredit() {
        Response response = addMoney(walletId, "key-1", 50000, ref("GW-DECLINED-1"), "MASTERCARD");

        assertError(response, 402, "PAYMENT_DECLINED");
        assertTrue(response.body().get("error").get("message").asString().contains("INSUFFICIENT_FUNDS"));
        assertEquals(0, ledgerEntries());
    }

    @Test
    void pendingPaymentDoesNotCredit() {
        assertError(addMoney(walletId, "key-1", 50000, ref("GW-PENDING-1"), "VISA"), 402, "PAYMENT_DECLINED");
        assertEquals(0, ledgerEntries());
    }

    @Test
    void unknownPaymentIsRejected() {
        assertError(addMoney(walletId, "key-1", 50000, ref("GW-NOTFOUND-1"), "VISA"), 422, "PAYMENT_NOT_FOUND");
    }

    @Test
    void paymentCapturedForDifferentAmountIsRejected() {
        assertError(addMoney(walletId, "key-1", 50000, ref("GW-MISMATCH-1"), "VISA"), 422, "PAYMENT_MISMATCH");
        assertEquals(0, ledgerEntries());
    }

    @Test
    void gatewayTimeoutIsReportedAsGatewayError() {
        assertError(addMoney(walletId, "key-1", 50000, ref("GW-TIMEOUT-1"), "VISA"), 502, "PAYMENT_GATEWAY_ERROR");
        assertEquals(0, ledgerEntries());
    }

    // ---- business rules ----------------------------------------------------------------------------

    @Test
    void gatewayRefCanCreditOnlyOnce() {
        addMoney(walletId, "key-1", 50000, ref("GW-1"), "VISA");
        Response duplicate = addMoney(walletId, "key-2", 50000, ref("GW-1"), "VISA");

        assertError(duplicate, 409, "DUPLICATE_GATEWAY_REF");
        assertEquals(1, gatewayCalls("visa"), "a known gateway_ref is rejected before calling the gateway");
        assertEquals(50000, walletService.getWallet(walletId).balance());
    }

    @Test
    void concurrentRequestsWithSameGatewayRefCreditOnce() throws Exception {
        List<Response> responses = concurrently(10, i -> addMoney(walletId, "key-" + i, 50000, ref("GW-1"), "VISA"));

        assertEquals(1, responses.stream().filter(r -> r.status() == 201).count());
        responses.stream().filter(r -> r.status() != 201)
                .forEach(r -> assertError(r, 409, "DUPLICATE_GATEWAY_REF"));
        assertEquals(50000, walletService.getWallet(walletId).balance());
    }

    @Test
    void concurrentCreditsToOneWalletAreAllApplied() throws Exception {
        List<Response> responses = concurrently(10, i -> addMoney(walletId, "key-" + i, 1000 * (i + 1), ref("GW-" + i),
                i % 2 == 0 ? "VISA" : "MASTERCARD"));

        responses.forEach(r -> assertEquals(201, r.status()));
        assertEquals(55000, walletService.getWallet(walletId).balance());
        assertEquals(10, ledgerEntries());
    }

    @Test
    void unknownWalletIsRejectedWithoutCallingGateway() {
        assertError(addMoney(UUID.randomUUID(), "key-1", 50000, ref("GW-1"), "VISA"), 404, "WALLET_NOT_FOUND");
        assertEquals(0, gatewayCalls("visa"));
    }

    @Test
    void walletIdIsRequiredInTheBody() {
        Response response = send("key-1", """
                {"amount": 50000, "gateway_ref": "%s", "payment_method": "VISA"}""".formatted(ref("GW-1")));

        assertError(response, 400, "VALIDATION_ERROR");
        assertEquals("walletId: wallet_id is required", response.body().get("error").get("message").asString());
        assertEquals(0, gatewayCalls("visa"));
    }

    @Test
    void walletIdMustBeAUuid() {
        Response response = send("key-1", """
                {"wallet_id": "w_123", "amount": 50000, "gateway_ref": "%s", "payment_method": "VISA"}"""
                .formatted(ref("GW-1")));

        assertError(response, 400, "INVALID_PAYLOAD");
        assertEquals("Invalid value for field: wallet_id", response.body().get("error").get("message").asString());
    }

    @Test
    void amountOutsideLimitsIsRejected() {
        assertError(addMoney(walletId, "key-1", 99, ref("GW-1"), "VISA"), 400, "VALIDATION_ERROR");
        assertError(addMoney(walletId, "key-2", 2_500_001, ref("GW-2"), "VISA"), 400, "VALIDATION_ERROR");
    }

    @Test
    void unsupportedPaymentMethodIsRejected() {
        Response response = addMoney(walletId, "key-1", 50000, ref("GW-1"), "AMEX");

        assertError(response, 400, "INVALID_PAYLOAD");
        assertEquals("Invalid value for field: payment_method", response.body().get("error").get("message").asString());
    }

    @Test
    void gatewayRefMustBeAUuid() {
        Response response = addMoney(walletId, "key-1", 50000, "GW-20261105-9981", "VISA");

        assertError(response, 400, "INVALID_PAYLOAD");
        assertEquals("Invalid value for field: gateway_ref", response.body().get("error").get("message").asString());
        assertEquals(0, gatewayCalls("visa"));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private record Response(int status, JsonNode body) {
    }

    private Response addMoney(UUID wallet, String key, long amount, String gatewayRef, String paymentMethod) {
        return send(key, body(wallet, amount, gatewayRef, paymentMethod));
    }

    private static String body(UUID wallet, long amount, String gatewayRef, String paymentMethod) {
        return """
                {"wallet_id": "%s", "amount": %d, "gateway_ref": "%s", "payment_method": "%s"}"""
                .formatted(wallet, amount, gatewayRef, paymentMethod);
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

    private long ledgerEntries() {
        return jdbcClient.sql("SELECT count(*) FROM wallet_transactions").query(Long.class).single();
    }

    private long idempotencyKeys() {
        return jdbcClient.sql("SELECT count(*) FROM idempotency_keys").query(Long.class).single();
    }

    /** Deterministic user_id (a UUID) for a readable test name. */
    private static UUID user(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** Mock-gateway scenario prefixes (see docker/wiremock): the first UUID group picks the outcome. */
    private static final Map<String, String> SCENARIO_PREFIXES = Map.of(
            "DECLINED", "00000402", "PENDING", "00000202", "NOTFOUND", "00000404",
            "MISMATCH", "00000422", "ERROR", "00000500", "TIMEOUT", "00000504");

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
