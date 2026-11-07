package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.common.constant.AppConstants;
import com.pathaopay.miniwallet.common.exception.transaction.SystemWalletException;
import com.pathaopay.miniwallet.dto.request.AddMoneyRequest;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.model.enums.PaymentMethod;
import com.pathaopay.miniwallet.model.enums.WalletType;
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

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two read endpoints: wallet details (static) and balance (derived from the ledger). */
class WalletApiIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PHONE = "01711000001";

    @LocalServerPort
    private int port;

    @Autowired
    private WalletService walletService;

    private RestClient api;

    @BeforeEach
    void setUp() {
        api = RestClient.create("http://localhost:" + port);
        walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), PHONE, WalletType.LIABILITY));
    }

    @Test
    void walletEndpointReturnsDetailsWithoutABalance() {
        JsonNode wallet = get("/api/v1/wallets/" + PHONE);

        assertEquals(7, UUID.fromString(wallet.get("wallet_id").asString()).version());
        assertEquals(PHONE, wallet.get("phone").asString());
        assertEquals("BDT", wallet.get("currency").asString());
        assertEquals("LIABILITY", wallet.get("type").asString());
        assertTrue(wallet.has("user_id"));
        assertTrue(wallet.has("created_at"));
        assertFalse(wallet.has("balance"));
        assertFalse(wallet.has("updated_at"));
    }

    @Test
    void balanceEndpointOfANewWalletIsZeroAsOfItsCreation() {
        JsonNode wallet = get("/api/v1/wallets/" + PHONE);
        JsonNode balance = get("/api/v1/wallets/" + PHONE + "/balance");

        assertEquals(wallet.get("wallet_id"), balance.get("wallet_id"));
        assertEquals(PHONE, balance.get("phone").asString());
        assertEquals("BDT", balance.get("currency").asString());
        assertEquals(0, balance.get("balance").asLong());
        assertEquals(wallet.get("created_at").asString(), balance.get("updated_at").asString());
    }

    @Test
    void balanceFollowsTheLatestLedgerEntry() {
        Instant before = Instant.parse(get("/api/v1/wallets/" + PHONE + "/balance").get("updated_at").asString());
        topUp(PHONE, 50000L, "k1");
        topUp(PHONE, 20000L, "k2");

        JsonNode balance = get("/api/v1/wallets/+880" + PHONE.substring(1) + "/balance");

        assertEquals(70000, balance.get("balance").asLong());
        assertTrue(Instant.parse(balance.get("updated_at").asString()).isAfter(before));
        assertEquals(balance, get("/api/v1/wallets/" + PHONE.substring(1) + "/balance"));
    }

    @Test
    void unknownAndInvalidPhonesAreRejected() {
        assertError("/api/v1/wallets/01999999999", 404, "WALLET_NOT_FOUND");
        assertError("/api/v1/wallets/01999999999/balance", 404, "WALLET_NOT_FOUND");
        assertError("/api/v1/wallets/not-a-phone", 400, "INVALID_PHONE_NUMBER");
        assertError("/api/v1/wallets/not-a-phone/balance", 400, "INVALID_PHONE_NUMBER");
    }

    @Test
    void walletTypeIsRequiredAndHasNoDefault() {
        Response response = createWallet("""
                {"user_id": "%s", "phone": "01811000009"}""".formatted(UUID.randomUUID()));

        assertError(response, 400, "VALIDATION_ERROR");
        assertEquals("type: type is required", response.body().get("error").get("message").asString());
    }

    @Test
    void unknownWalletTypeIsRejected() {
        Response response = createWallet(body(UUID.randomUUID(), "01811000009", "SAVINGS"));

        assertError(response, 400, "INVALID_PAYLOAD");
        assertEquals("Invalid value for field: type", response.body().get("error").get("message").asString());
    }

    @Test
    void createsACustomerWalletOfTypeLiability() {
        Response response = createWallet(body(UUID.randomUUID(), "01811000009", "LIABILITY"));

        assertEquals(201, response.status(), response.body().toString());
        assertEquals("LIABILITY", response.body().get("type").asString());
        assertEquals("LIABILITY", get("/api/v1/wallets/01811000009").get("type").asString());
    }

    @Test
    void anyUserCanCreateAWalletOfAnyType() {
        UUID user = UUID.randomUUID();
        int number = 10;
        for (WalletType type : WalletType.values()) {
            String phone = "018110000" + number++;
            UUID owner = type == WalletType.LIABILITY ? user : UUID.randomUUID();
            Response response = createWallet(body(owner, phone, type.name()));

            assertEquals(201, response.status(), response.body().toString());
            assertEquals(type.name(), response.body().get("type").asString());
            assertEquals(owner.toString(), response.body().get("user_id").asString());
            assertEquals(type.name(), get("/api/v1/wallets/" + phone).get("type").asString());
            assertEquals(0, get("/api/v1/wallets/" + phone + "/balance").get("balance").asLong());
        }
    }

    @Test
    void thePhoneRuleIsTheSameForEveryTypeAnyValidBangladeshiNumber() {
        assertEquals(201, createWallet(body(UUID.randomUUID(), "01711000077", "EXPENSE")).status());
        assertEquals(201, createWallet(body(UUID.randomUUID(), "01011000078", "LIABILITY")).status());
        assertEquals(201, createWallet(body(UUID.randomUUID(), "01211000079", "ASSET")).status());
        for (String type : new String[]{"EXPENSE", "LIABILITY"}) {
            UUID owner = UUID.randomUUID();
            assertError(createWallet(body(owner, "12345", type)), 400, "VALIDATION_ERROR");
        }
    }

    @Test
    void aCustomerCannotTakeASystemWalletsNumber() {
        assertError(createWallet(body(UUID.randomUUID(), AppConstants.REVENUE_WALLET_PHONE, "LIABILITY")), 409, "DUPLICATE_PHONE");
    }

    @Test
    void oneUserCanOwnManyWalletsOfAnyTypeAndOnlyThePhoneIsUnique() {
        UUID user = UUID.randomUUID();
        assertEquals(201, createWallet(body(user, "01000000021", "ASSET")).status());
        assertEquals(201, createWallet(body(user, "01000000022", "ASSET")).status());
        assertEquals(201, createWallet(body(user, "01811000023", "LIABILITY")).status());
        assertEquals(201, createWallet(body(user, "01811000024", "LIABILITY")).status());
        assertError(createWallet(body(user, "01000000021", "EQUITY")), 409, "DUPLICATE_PHONE");
        assertError(createWallet(body(UUID.randomUUID(), "01811000024", "LIABILITY")), 409, "DUPLICATE_PHONE");
    }

    @Test
    void aCreatedSystemClassWalletStillCannotBeToppedUp() {
        createWallet(body(UUID.randomUUID(), "01000000031", "REVENUE"));

        assertThrows(SystemWalletException.class, () -> transactionService.addMoney(
                new AddMoneyRequest("01000000031", 50000L, UUID.randomUUID(), PaymentMethod.VISA), "key-system"));
    }

    @Test
    void unsupportedContentTypeIs415InTheStandardErrorFormat() {
        api.post().uri("/api/v1/wallets").contentType(MediaType.TEXT_PLAIN).body("x")
                .exchange((request, response) -> {
                    JsonNode body = JSON.readTree(response.getBody());
                    assertEquals(415, response.getStatusCode().value(), body.toString());
                    assertEquals("UNSUPPORTED_MEDIA_TYPE", body.get("error").get("code").asString());
                    return null;
                });
    }

    @Test
    void unacceptableAcceptHeaderIs406InTheStandardErrorFormat() {
        api.get().uri("/api/v1/wallets/" + PHONE).accept(MediaType.APPLICATION_XML)
                .exchange((request, response) -> {
                    JsonNode body = JSON.readTree(response.getBody());
                    assertEquals(406, response.getStatusCode().value(), body.toString());
                    assertEquals("NOT_ACCEPTABLE", body.get("error").get("code").asString());
                    return null;
                });
    }

    private record Response(int status, JsonNode body) {
    }

    private static String body(UUID userId, String phone, String type) {
        return """
                {"user_id": "%s", "phone": "%s", "type": "%s"}""".formatted(userId, phone, type);
    }

    private Response createWallet(String json) {
        return api.post().uri("/api/v1/wallets").contentType(MediaType.APPLICATION_JSON).body(json)
                .exchange((request, response) -> new Response(response.getStatusCode().value(),
                        JSON.readTree(response.getBody())));
    }

    private static void assertError(Response response, int status, String code) {
        assertEquals(status, response.status(), response.body().toString());
        assertEquals(code, response.body().get("error").get("code").asString());
    }

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
}
