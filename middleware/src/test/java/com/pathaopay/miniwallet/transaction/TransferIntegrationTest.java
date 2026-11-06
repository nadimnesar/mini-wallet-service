package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.common.constant.DbConstants.WalletTransactions;
import com.pathaopay.miniwallet.dto.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.model.PaymentMethod;
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
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port);
        alice = wallet("01711000001");
        bob = wallet("01711000002");
        fund(alice, 50000);
    }

    @Test
    void movesMoneyAndWritesBothLedgerLegs() {
        Response response = transfer("key-1", alice, bob, 10000, "Lunch");

        assertEquals(201, response.status());
        assertEquals(alice.toString(), response.body().get("from_wallet_id").asString());
        assertEquals(bob.toString(), response.body().get("to_wallet_id").asString());
        assertEquals(10000, response.body().get("amount").asLong());
        assertEquals("COMPLETED", response.body().get("status").asString());
        assertEquals(40000, balance(alice));
        assertEquals(10000, balance(bob));

        UUID transferId = UUID.fromString(response.body().get("transfer_id").asString());
        assertEquals(2, jdbcClient.sql("SELECT count(*) FROM " + WalletTransactions.TABLE + " WHERE "
                + WalletTransactions.REFERENCE_ID + " = :id").param("id", transferId).query(Long.class).single());
    }

    @Test
    void sameKeyAndBodyReplaysOriginalResponse() {
        Response first = transfer("key-1", alice, bob, 10000, "Lunch");
        Response replay = transfer("key-1", alice, bob, 10000, "Lunch");

        assertEquals(201, replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(40000, balance(alice));
        assertEquals(10000, balance(bob));
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        transfer("key-1", alice, bob, 10000, "Lunch");
        Response reused = transfer("key-1", alice, bob, 20000, "Lunch");

        assertError(reused, 422, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(40000, balance(alice));
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
    void transferringToTheSameWalletIsRejected() {
        assertError(transfer("key-1", alice, alice, 10000, null), 422, "SAME_WALLET_TRANSFER");
        assertEquals(50000, balance(alice));
    }

    @Test
    void unknownWalletsAreRejected() {
        assertError(transfer("key-1", UUID.randomUUID(), bob, 10000, null), 404, "WALLET_NOT_FOUND");
        assertError(transfer("key-2", alice, UUID.randomUUID(), 10000, null), 404, "WALLET_NOT_FOUND");
        assertEquals(50000, balance(alice));
    }

    @Test
    void amountOutsideLimitsIsRejected() {
        assertError(transfer("key-1", alice, bob, 99, null), 400, "VALIDATION_ERROR");
        assertError(transfer("key-2", alice, bob, 2_500_001, null), 400, "VALIDATION_ERROR");
    }

    @Test
    void walletIdsMustBeUuids() {
        Response response = send("key-1", """
                {"from_wallet_id": "w_aaa", "to_wallet_id": "%s", "amount": 10000}""".formatted(bob));

        assertError(response, 400, "INVALID_PAYLOAD");
        assertEquals("Invalid value for field: from_wallet_id", response.body().get("error").get("message").asString());
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
        assertEquals(40000, balance(alice));
        assertEquals(10000, balance(bob));
    }

    @Test
    void concurrentTransfersCannotOverdrawTheSender() throws Exception {
        List<Response> responses = concurrently(10, i -> transfer("key-" + i, alice, bob, 10000, null));

        assertEquals(5, responses.stream().filter(r -> r.status() == 201).count());
        responses.stream().filter(r -> r.status() != 201).forEach(r -> assertError(r, 422, "INSUFFICIENT_BALANCE"));
        assertEquals(0, balance(alice));
        assertEquals(50000, balance(bob));
    }

    @Test
    void oppositeTransfersBetweenTheSameWalletsDoNotDeadlockAndConserveMoney() throws Exception {
        fund(bob, 50000);
        List<Response> responses = concurrently(20, i -> i % 2 == 0
                ? transfer("key-" + i, alice, bob, 1000, null)
                : transfer("key-" + i, bob, alice, 1000, null));

        responses.forEach(r -> assertEquals(201, r.status(), r.body().toString()));
        assertEquals(100000, balance(alice) + balance(bob));
        assertEquals(50000, balance(alice));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private record Response(int status, JsonNode body) {
    }

    private UUID wallet(String phone) {
        return walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), phone)).walletId();
    }

    private void fund(UUID walletId, long amount) {
        transactionService.addMoney(new AddMoneyRequest(walletId, amount, UUID.randomUUID(), PaymentMethod.VISA),
                "fund-" + UUID.randomUUID());
    }

    private long balance(UUID walletId) {
        return walletService.getWallet(walletId).balance();
    }

    private Response transfer(String key, UUID from, UUID to, long amount, String note) {
        return send(key, """
                {"from_wallet_id": "%s", "to_wallet_id": "%s", "amount": %d%s}"""
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
