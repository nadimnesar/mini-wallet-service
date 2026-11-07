package com.pathaopay.miniwallet.adversarial;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.constant.DbConstants.Transactions;
import com.pathaopay.miniwallet.common.constant.MessagingConstants;
import com.pathaopay.miniwallet.dto.request.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.response.AddMoneyStatusResponse;
import com.pathaopay.miniwallet.model.enums.AddMoneyStatus;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.service.WalletService;
import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A misbehaving payment gateway, a poisoned queue, duplicate deliveries and contention, all against the real
 * PostgreSQL, RabbitMQ and WireMock containers. Money must never be created, lost or credited twice, and no request
 * may be left PENDING forever.
 */
class HostileInfrastructureIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    private String wallet(String phone) {
        return walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), phone, WalletType.LIABILITY)).phone();
    }

    private long balance(String phone) {
        return walletService.getBalance(phone).balance();
    }

    private long addMoneyTransactions() {
        return jdbcClient.sql("SELECT count(*) FROM " + Transactions.TABLE + " WHERE " + Transactions.TYPE
                + " = 'ADD_MONEY'").query(Long.class).single();
    }

    @AfterEach
    void restoreGatewayStubs() {
        RestClient.create(paymentGatewayUrl()).post().uri("/__admin/mappings/reset").retrieve().toBodilessEntity();
    }

    private void stubGateway(String refPrefix, String responseJson) {
        RestClient.create(paymentGatewayUrl()).post().uri("/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"priority": 1,
                         "request": {"method": "POST", "urlPathPattern": "/(visa|mastercard)/v1/payments/verify",
                           "bodyPatterns": [{"matchesJsonPath": {"expression": "$.gateway_ref", "matches": "^%s-.*"}}]},
                         "response": %s}""".formatted(refPrefix, responseJson))
                .retrieve().toBodilessEntity();
    }

    private AddMoneyStatusResponse addMoney(String phone, long amount, UUID ref) {
        return transactionService.addMoney(new AddMoneyRequest(phone, amount, ref, PaymentMethod.VISA),
                "key-" + UUID.randomUUID());
    }

    private static UUID refWithPrefix(String prefix) {
        return UUID.fromString(prefix + "-0000-4000-8000-" + "%012d".formatted(Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 1_000_000_000_000L));
    }

    @Test
    void aGatewayThatAnswersNonsenseNeverCreditsAndTheRequestAlwaysEndsFailed() {
        String alice = wallet("01711000001");
        List<String> responses = List.of(
                """
                {"status": 200, "body": "<html>maintenance</html>", "headers": {"Content-Type": "text/html"}}""",
                """
                {"status": 200, "body": "{}", "headers": {"Content-Type": "application/json"}}""",
                """
                {"status": 200, "body": "{\\"status\\": \\"TELEPORTED\\", \\"amount\\": 1000}", "headers": {"Content-Type": "application/json"}}""",
                """
                {"status": 200, "body": "{\\"status\\": \\"CAPTURED\\", \\"amount\\": -1000, \\"currency\\": \\"BDT\\"}", "headers": {"Content-Type": "application/json"}}""",
                """
                {"status": 200, "body": "", "headers": {"Content-Type": "application/json"}}""",
                """
                {"fault": "CONNECTION_RESET_BY_PEER"}""",
                """
                {"fault": "MALFORMED_RESPONSE_CHUNK"}""",
                """
                {"fault": "EMPTY_RESPONSE"}""",
                """
                {"status": 302, "headers": {"Location": "http://127.0.0.1:1/elsewhere"}}""",
                """
                {"status": 401, "body": "{\\"error\\": \\"INVALID_API_KEY\\"}", "headers": {"Content-Type": "application/json"}}""");
        for (int i = 0; i < responses.size(); i++) {
            String prefix = "0000099" + i;
            stubGateway(prefix, responses.get(i));

            AddMoneyStatusResponse accepted = addMoney(alice, 1000, refWithPrefix(prefix));
            AddMoneyStatusResponse finished = awaitFinished(accepted.requestId());

            assertEquals(AddMoneyStatus.FAILED, finished.status(), "stub " + i + ": " + finished);
            assertNotNull(finished.errorCode(), "stub " + i);
        }
        assertEquals(0, balance(alice));
        assertEquals(0, addMoneyTransactions());
    }

    @Test
    void aGatewayThatConfirmsTheWrongCurrencyOrReferenceIsRejected() {
        String alice = wallet("01711000001");
        UUID ref = refWithPrefix("0000098" + 1);
        stubGateway("00000981", """
                {"status": 200, "headers": {"Content-Type": "application/json"},
                 "jsonBody": {"gateway_ref": "%s", "status": "CAPTURED", "amount": 1000, "currency": "USD",
                              "merchant_reference": "%s", "card_network": "VISA"}}""".formatted(ref, alice));
        UUID otherRef = refWithPrefix("0000098" + 2);
        stubGateway("00000982", """
                {"status": 200, "headers": {"Content-Type": "application/json"},
                 "jsonBody": {"gateway_ref": "%s", "status": "CAPTURED", "amount": 1000, "currency": "BDT",
                              "merchant_reference": "%s", "card_network": "VISA"}}""".formatted(UUID.randomUUID(), alice));

        assertEquals(AddMoneyStatus.FAILED, awaitFinished(addMoney(alice, 1000, ref).requestId()).status());
        assertEquals(AddMoneyStatus.FAILED, awaitFinished(addMoney(alice, 1000, otherRef).requestId()).status());
        assertEquals(0, balance(alice));
    }

    @Test
    void garbageMessagesOnTheWorkQueueAreDroppedAndTheWorkerKeepsServing() {
        String alice = wallet("01711000001");
        for (String garbage : List.of("not-a-uuid", "", "{\"json\": true}", UUID.randomUUID().toString(),
                "00000000-0000-0000-0000-000000000000", "x".repeat(100_000))) {
            rabbitTemplate.convertAndSend(MessagingConstants.ADD_MONEY_QUEUE, garbage);
        }
        rabbitTemplate.convertAndSend(MessagingConstants.ADD_MONEY_QUEUE, new byte[]{0, 1, 2, (byte) 0xff});

        topUp(alice, 10000, "after-garbage");

        assertEquals(10000, balance(alice));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var info = rabbitAdmin.getQueueInfo(MessagingConstants.ADD_MONEY_QUEUE);
            assertEquals(0, info.getMessageCount(), "the work queue must drain, no poison message may loop");
        });
    }

    @Test
    void aStormOfDuplicateDeliveriesCreditsExactlyOnce() {
        String alice = wallet("01711000001");
        AddMoneyStatusResponse accepted = addMoney(alice, 12345, UUID.randomUUID());
        for (int i = 0; i < 40; i++) {
            rabbitTemplate.convertAndSend(MessagingConstants.ADD_MONEY_QUEUE, accepted.requestId().toString());
        }

        AddMoneyStatusResponse finished = awaitFinished(accepted.requestId());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(0, rabbitAdmin.getQueueInfo(MessagingConstants.ADD_MONEY_QUEUE).getMessageCount()));

        assertEquals(AddMoneyStatus.COMPLETED, finished.status());
        assertEquals(12345, balance(alice));
        assertEquals(1, addMoneyTransactions());
        assertEquals(12345, balance(AppConstants.GATEWAY_CLEARING_WALLET_PHONE));
    }

    @Test
    void oneGatewayRefRacedAcrossManyWalletsIsAcceptedAndCreditedOnce() throws Exception {
        List<String> wallets = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            wallets.add(wallet("0171100010" + i));
        }
        UUID ref = UUID.randomUUID();
        List<Boolean> accepted = concurrently(10, i -> {
            try {
                addMoney(wallets.get(i), 5000, ref);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });

        assertEquals(1, accepted.stream().filter(b -> b).count());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(1, addMoneyTransactions()));
        long total = wallets.stream().mapToLong(this::balance).sum();
        assertEquals(5000, total);
    }

    @Test
    void manyConcurrentTopUpsAndTransfersLeaveTheBooksExactlyBalanced() throws Exception {
        List<String> wallets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            wallets.add(wallet("0171100020" + i));
        }
        List<Boolean> done = concurrently(30, i -> {
            AddMoneyStatusResponse accepted = addMoney(wallets.get(i % 6), 10000, UUID.randomUUID());
            return awaitFinished(accepted.requestId()).status() == AddMoneyStatus.COMPLETED;
        });
        assertTrue(done.stream().allMatch(b -> b));

        concurrently(60, i -> {
            try {
                transactionService.transfer(new com.pathaopay.miniwallet.dto.request.TransferRequest(
                        wallets.get(i % 6), wallets.get((i * 5 + 1) % 6), 7000L, null), "t-" + i);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });

        long customers = wallets.stream().mapToLong(this::balance).sum();
        long revenue = balance(AppConstants.REVENUE_WALLET_PHONE);
        assertEquals(30 * 10000, customers + revenue);
        assertEquals(30 * 10000, balance(AppConstants.GATEWAY_CLEARING_WALLET_PHONE));
        assertEquals(0, wallets.stream().filter(w -> balance(w) < 0).count());
    }

    private interface Task<T> {
        T run(int index) throws Exception;
    }

    private static <T> List<T> concurrently(int count, Task<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.run(index);
                }));
            }
            start.countDown();
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }
}
