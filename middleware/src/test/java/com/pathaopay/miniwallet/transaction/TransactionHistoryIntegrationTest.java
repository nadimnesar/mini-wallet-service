package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.dto.request.TransferRequest;
import com.pathaopay.miniwallet.service.WalletService;
import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction history and detail endpoints, reading the ledger written by add-money and transfers. */
class TransactionHistoryIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private WalletService walletService;

    private RestClient api;
    private String alice;
    private String bob;
    private UUID transferId;

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port);
        alice = walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01711000001", WalletType.LIABILITY)).phone();
        bob = walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01711000002", WalletType.LIABILITY)).phone();
        topUp(alice, 50000L, "k1");
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
        assertEquals(5, get("/api/v1/wallets/" + alice + "/transactions?from=2000-01-01T00:00:00Z&to=2999-01-01T00:00:00Z")
                .get("total_items").asLong());
        assertEquals(0, get("/api/v1/wallets/" + alice + "/transactions?to=2000-01-01T00:00:00Z")
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
    void aWalletsHistoryFollowsItsLedgerChainEvenWhenTimestampsDisagree() {
        String carol = walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01711000003", WalletType.LIABILITY)).phone();
        UUID carolId = jdbcClient.sql("SELECT wallet_id FROM wallets WHERE phone = :p").param("p", carol)
                .query(UUID.class).single();
        // Entry 1 is stamped later than entry 2, as when a slow add-money starts before a transfer that commits first.
        postTopUp(carolId, 1, 10000, 0, Instant.parse("2026-01-02T00:00:00Z"));
        postTopUp(carolId, 2, 5000, 10000, Instant.parse("2026-01-01T00:00:00Z"));

        JsonNode page = get("/api/v1/wallets/" + carol + "/transactions");

        assertEquals(List.of(15000L, 10000L), balancesAfter(page), "newest ledger entry first, balances never jump back");
    }

    @Test
    void entriesAreStampedWhenTheyAreWrittenSoTimestampsFollowTheChain() {
        long outOfOrder = jdbcClient.sql("SELECT count(*) FROM (SELECT created_at, lag(created_at) OVER ("
                + "PARTITION BY wallet_id ORDER BY entry_no) AS previous FROM ledger_entries) e "
                + "WHERE previous > created_at").query(Long.class).single();

        assertEquals(0, outOfOrder);
        assertTrue(jdbcClient.sql("SELECT count(*) FROM ledger_entries").query(Long.class).single() > 0);
    }

    /** A top-up journal inserted with an explicit timestamp (a data-modifying CTE, so the balance check sees all of it). */
    private void postTopUp(UUID walletId, long entryNo, long amount, long before, Instant at) {
        UUID clearing = AppConstants.GATEWAY_CLEARING_WALLET_ID;
        long[] latest = jdbcClient.sql("SELECT entry_no, balance_after FROM ledger_entries WHERE wallet_id = :w "
                        + "ORDER BY entry_no DESC LIMIT 1").param("w", clearing)
                .query((rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}).single();
        UUID transactionId = UUID.randomUUID();
        jdbcClient.sql("WITH tx AS (INSERT INTO transactions (id, type, amount, gateway_ref, payment_method, created_at) "
                        + "VALUES (:tx, 'ADD_MONEY', :amount, :tx, 'VISA', :at)) "
                        + "INSERT INTO ledger_entries (id, wallet_id, wallet_type, entry_no, type, direction, amount, "
                        + "balance_before, balance_after, counterparty_wallet_id, transaction_id, created_at) VALUES "
                        + "(:id1, :w, 'LIABILITY', :n, 'ADD_MONEY', 'CREDIT', :amount, :before, :after, :clearing, :tx, :at), "
                        + "(:id2, :clearing, 'ASSET', :cn, 'GATEWAY_CLEARING', 'DEBIT', :amount, :cbefore, :cafter, :w, :tx, :at)")
                .param("tx", transactionId).param("amount", amount)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("id1", UUID.randomUUID()).param("id2", UUID.randomUUID())
                .param("w", walletId).param("n", entryNo).param("before", before).param("after", before + amount)
                .param("clearing", clearing).param("cn", latest[0] + 1)
                .param("cbefore", latest[1]).param("cafter", latest[1] + amount)
                .update();
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
        assertError("/api/v1/wallets/" + alice + "/transactions?type=BOGUS", 400, "INVALID_ARGUMENT");
        assertError("/api/v1/wallets/" + alice + "/transactions?page=0", 400, "VALIDATION_ERROR");
        assertError("/api/v1/wallets/" + alice + "/transactions?page_size=101", 400, "VALIDATION_ERROR");
        assertError("/api/v1/wallets/" + alice + "/transactions?from=2999-01-01T00:00:00Z&to=2000-01-01T00:00:00Z", 400, "INVALID_DATE_RANGE");
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
