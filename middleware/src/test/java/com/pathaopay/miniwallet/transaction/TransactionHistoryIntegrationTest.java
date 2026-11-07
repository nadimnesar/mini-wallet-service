package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.dto.request.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.dto.request.TransferRequest;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;
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
    private String alice;
    private String bob;
    private UUID transferId;

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port);
        alice = walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01711000001")).phone();
        bob = walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01711000002")).phone();
        transactionService.addMoney(new AddMoneyRequest(alice, 50000L, UUID.randomUUID(), PaymentMethod.VISA), "k1");
        transferId = UUID.fromString(transactionService
                .transfer(new TransferRequest(alice, bob, 10000L, "Lunch"), "k2").transferId().toString());
        transactionService.transfer(new TransferRequest(alice, bob, 5000L, null), "k3");
    }

    @Test
    void walletHistoryIsNewestFirstWithBalanceCounterpartyAndReference() {
        JsonNode page = get("/api/v1/wallets/" + alice + "/transactions");

        assertEquals(5, page.get("total_items").asLong());
        assertEquals(1, page.get("total_pages").asInt());
        assertEquals(20, page.get("page_size").asInt());
        assertEquals(List.of("FEE_OUT", "TRANSFER_OUT", "FEE_OUT", "TRANSFER_OUT", "ADD_MONEY"), types(page));
        assertEquals("DEBIT", page.get("items").get(0).get("direction").asString());
        assertEquals("CREDIT", page.get("items").get(4).get("direction").asString());
        assertEquals(List.of(34000L, 34500L, 39500L, 40000L, 50000L), balancesAfter(page));
        JsonNode second = page.get("items").get(3);
        assertEquals(bob, second.get("counterparty_phone").asString());
        assertEquals(alice, second.get("phone").asString());
        assertEquals(walletService.getWallet(alice).walletId().toString(), second.get("wallet_id").asString());
        assertEquals(walletService.getWallet(bob).walletId().toString(), second.get("counterparty_wallet_id").asString());
        assertEquals(transferId.toString(), second.get("transaction_id").asString());
        assertEquals(10000, second.get("amount").asLong());
    }

    @Test
    void receiverSeesTransferIn() {
        JsonNode page = get("/api/v1/wallets/" + bob + "/transactions");

        assertEquals(List.of("TRANSFER_IN", "TRANSFER_IN"), types(page));
        assertEquals(alice, page.get("items").get(0).get("counterparty_phone").asString());
    }

    @Test
    void filtersByType() {
        JsonNode page = get("/api/v1/wallets/" + alice + "/transactions?type=ADD_MONEY");

        assertEquals(List.of("ADD_MONEY"), types(page));
        assertEquals(1, page.get("total_items").asLong());
    }

    @Test
    void filtersByDateRange() {
        assertEquals(5, get("/api/v1/transactions?phone=" + alice + "&from=2000-01-01T00:00:00Z&to=2999-01-01T00:00:00Z")
                .get("total_items").asLong());
        assertEquals(0, get("/api/v1/transactions?phone=" + alice + "&to=2000-01-01T00:00:00Z")
                .get("total_items").asLong());
    }

    @Test
    void phoneInAnyAcceptedFormatSelectsTheWallet() {
        assertEquals(5, get("/api/v1/wallets/+880" + alice.substring(1) + "/transactions").get("total_items").asLong());
        assertEquals(5, get("/api/v1/wallets/" + alice.substring(1) + "/transactions").get("total_items").asLong());
    }

    @Test
    void paginates() {
        JsonNode first = get("/api/v1/wallets/" + alice + "/transactions?page_size=2");
        JsonNode second = get("/api/v1/wallets/" + alice + "/transactions?page_size=2&page=3");

        assertEquals(2, first.get("items").size());
        assertEquals(3, first.get("total_pages").asInt());
        assertEquals(List.of("ADD_MONEY"), types(second));
        assertEquals(5, second.get("total_items").asLong());
    }

    @Test
    void transactionsEndpointListsAcrossWalletsWhenNoWalletGiven() {
        // alice: add-money, 2 x (out + fee); bob: 2 in; revenue: 2 fees in; clearing: 1 debit
        assertEquals(10, get("/api/v1/transactions").get("total_items").asLong());
    }

    @Test
    void getsOneTransaction() {
        JsonNode entry = get("/api/v1/wallets/" + alice + "/transactions?type=ADD_MONEY").get("items").get(0);
        String id = entry.get("transaction_id").asString();

        JsonNode detail = get("/api/v1/transactions/" + id);

        assertEquals(id, detail.get("transaction_id").asString());
        assertEquals("ADD_MONEY", detail.get("type").asString());
        assertEquals(50000, detail.get("amount").asLong());
        assertEquals("VISA", detail.get("payment_method").asString());
        assertEquals(2, detail.get("entries").size());
        assertEquals(List.of("CREDIT", "DEBIT"), List.of(
                entryOf(detail, "ADD_MONEY").get("direction").asString(),
                entryOf(detail, "GATEWAY_CLEARING").get("direction").asString()));
        assertEquals(entry.get("entry_id").asString(), entryOf(detail, "ADD_MONEY").get("entry_id").asString());
        assertEquals(alice, entryOf(detail, "ADD_MONEY").get("phone").asString());
        assertEquals(AppConstants.GATEWAY_CLEARING_WALLET_PHONE,
                entryOf(detail, "ADD_MONEY").get("counterparty_phone").asString());
        assertEquals(50000, entryOf(detail, "ADD_MONEY").get("balance_after").asLong());
    }

    @Test
    void transferTransactionGroupsItsFourEntries() {
        JsonNode detail = get("/api/v1/transactions/" + transferId);

        assertEquals("TRANSFER", detail.get("type").asString());
        assertEquals(10000, detail.get("amount").asLong());
        assertEquals("Lunch", detail.get("note").asString());
        assertEquals(4, detail.get("entries").size());
        assertEquals(List.of("FEE_IN", "FEE_OUT", "TRANSFER_IN", "TRANSFER_OUT"), sortedEntryTypes(detail));
    }

    @Test
    void rejectsBadRequests() {
        assertError("/api/v1/transactions/" + UUID.randomUUID(), 404, "TRANSACTION_NOT_FOUND");
        assertError("/api/v1/transactions/not-a-uuid", 400, "INVALID_ARGUMENT");
        assertError("/api/v1/wallets/01999999999/transactions", 404, "WALLET_NOT_FOUND");
        assertError("/api/v1/wallets/not-a-phone/transactions", 400, "INVALID_PHONE_NUMBER");
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

    private static JsonNode entryOf(JsonNode detail, String type) {
        for (JsonNode e : detail.get("entries")) {
            if (type.equals(e.get("type").asString())) {
                return e;
            }
        }
        throw new AssertionError("no " + type + " entry in " + detail);
    }

    private static List<String> sortedEntryTypes(JsonNode detail) {
        List<String> types = new ArrayList<>();
        detail.get("entries").forEach(e -> types.add(e.get("type").asString()));
        return types.stream().sorted().toList();
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
