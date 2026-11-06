package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.dto.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.TransferRequest;
import com.pathaopay.miniwallet.model.PaymentMethod;
import com.pathaopay.miniwallet.service.TransactionService;
import com.pathaopay.miniwallet.service.WalletService;
import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Transaction history and detail endpoints, reading the ledger written by add-money and transfers. */
class TransactionHistoryIntegrationTest extends AbstractIntegrationTest {

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
    private UUID transferId;

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port);
        alice = walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01711000001")).walletId();
        bob = walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01711000002")).walletId();
        transactionService.addMoney(new AddMoneyRequest(alice, 50000L, UUID.randomUUID(), PaymentMethod.VISA), "k1");
        transferId = UUID.fromString(transactionService
                .transfer(new TransferRequest(alice, bob, 10000L, null), "k2").transferId().toString());
        transactionService.transfer(new TransferRequest(alice, bob, 5000L, null), "k3");
    }

    @Test
    void walletHistoryIsNewestFirstWithBalanceCounterpartyAndReference() {
        JsonNode page = get("/api/v1/wallets/" + alice + "/transactions");

        assertEquals(3, page.get("total_items").asLong());
        assertEquals(1, page.get("total_pages").asInt());
        assertEquals(20, page.get("page_size").asInt());
        assertEquals(List.of("TRANSFER_OUT", "TRANSFER_OUT", "ADD_MONEY"), types(page));
        assertEquals(List.of(35000L, 40000L, 50000L), balancesAfter(page));
        JsonNode second = page.get("items").get(1);
        assertEquals(bob.toString(), second.get("counterparty_wallet_id").asString());
        assertEquals(transferId.toString(), second.get("reference_id").asString());
        assertEquals(10000, second.get("amount").asLong());
    }

    @Test
    void receiverSeesTransferIn() {
        JsonNode page = get("/api/v1/wallets/" + bob + "/transactions");

        assertEquals(List.of("TRANSFER_IN", "TRANSFER_IN"), types(page));
        assertEquals(alice.toString(), page.get("items").get(0).get("counterparty_wallet_id").asString());
    }

    @Test
    void filtersByType() {
        JsonNode page = get("/api/v1/wallets/" + alice + "/transactions?type=ADD_MONEY");

        assertEquals(List.of("ADD_MONEY"), types(page));
        assertEquals(1, page.get("total_items").asLong());
    }

    @Test
    void filtersByDateRange() {
        assertEquals(3, get("/api/v1/transactions?wallet_id=" + alice + "&from=2000-01-01T00:00:00Z&to=2999-01-01T00:00:00Z")
                .get("total_items").asLong());
        assertEquals(0, get("/api/v1/transactions?wallet_id=" + alice + "&to=2000-01-01T00:00:00Z")
                .get("total_items").asLong());
    }

    @Test
    void paginates() {
        JsonNode first = get("/api/v1/wallets/" + alice + "/transactions?page_size=2");
        JsonNode second = get("/api/v1/wallets/" + alice + "/transactions?page_size=2&page=2");

        assertEquals(2, first.get("items").size());
        assertEquals(2, first.get("total_pages").asInt());
        assertEquals(List.of("ADD_MONEY"), types(second));
        assertEquals(3, second.get("total_items").asLong());
    }

    @Test
    void transactionsEndpointListsAcrossWalletsWhenNoWalletGiven() {
        assertEquals(5, get("/api/v1/transactions").get("total_items").asLong());
    }

    @Test
    void getsOneTransaction() {
        String id = get("/api/v1/wallets/" + alice + "/transactions?type=ADD_MONEY").get("items").get(0)
                .get("transaction_id").asString();

        JsonNode detail = get("/api/v1/transactions/" + id);

        assertEquals(id, detail.get("transaction_id").asString());
        assertEquals(alice.toString(), detail.get("wallet_id").asString());
        assertEquals("ADD_MONEY", detail.get("type").asString());
        assertEquals(50000, detail.get("balance_after").asLong());
    }

    @Test
    void rejectsBadRequests() {
        assertError("/api/v1/transactions/" + UUID.randomUUID(), 404, "TRANSACTION_NOT_FOUND");
        assertError("/api/v1/transactions/not-a-uuid", 400, "INVALID_ARGUMENT");
        assertError("/api/v1/wallets/" + UUID.randomUUID() + "/transactions", 404, "WALLET_NOT_FOUND");
        assertError("/api/v1/transactions?type=BOGUS", 400, "INVALID_ARGUMENT");
        assertError("/api/v1/transactions?page=0", 400, "VALIDATION_ERROR");
        assertError("/api/v1/transactions?page_size=101", 400, "VALIDATION_ERROR");
        assertError("/api/v1/transactions?from=2999-01-01T00:00:00Z&to=2000-01-01T00:00:00Z", 400, "INVALID_DATE_RANGE");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private JsonNode get(String uri) {
        return api.get().uri(uri).retrieve().body(JsonNode.class);
    }

    private void assertError(String uri, int status, String code) {
        api.get().uri(uri).exchange((request, response) -> {
            JsonNode body = JSON.readTree(response.getBody());
            assertEquals(status, response.getStatusCode().value(), uri + " " + body);
            assertEquals(code, body.get("error").get("code").asString(), uri);
            return null;
        });
    }

    private static List<String> types(JsonNode page) {
        List<String> types = new ArrayList<>();
        page.get("items").forEach(i -> types.add(i.get("type").asString()));
        return types;
    }

    private static List<Long> balancesAfter(JsonNode page) {
        List<Long> balances = new ArrayList<>();
        page.get("items").forEach(i -> balances.add(i.get("balance_after").asLong()));
        return balances;
    }
}
