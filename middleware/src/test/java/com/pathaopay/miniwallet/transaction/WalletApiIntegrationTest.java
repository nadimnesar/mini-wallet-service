package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.common.constant.AppConstants;
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

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), PHONE));
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
    void reservedSystemUserIdIsAValidationErrorNotADuplicate() {
        api.post().uri("/api/v1/wallets").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"user_id": "%s", "phone": "01811000009"}""".formatted(AppConstants.SYSTEM_USER_ID))
                .exchange((request, response) -> {
                    JsonNode body = JSON.readTree(response.getBody());
                    assertEquals(400, response.getStatusCode().value(), body.toString());
                    assertEquals("VALIDATION_ERROR", body.get("error").get("code").asString());
                    return null;
                });
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
