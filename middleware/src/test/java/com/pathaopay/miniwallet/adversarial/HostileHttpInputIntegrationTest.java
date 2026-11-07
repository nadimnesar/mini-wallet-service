package com.pathaopay.miniwallet.adversarial;

import com.pathaopay.miniwallet.common.constant.DbConstants.Transactions;
import com.pathaopay.miniwallet.common.constant.DbConstants.Wallets;
import com.pathaopay.miniwallet.dto.request.CreateWalletRequest;
import com.pathaopay.miniwallet.model.enums.WalletType;
import com.pathaopay.miniwallet.service.WalletService;
import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Throws malformed, oversized and malicious requests at the real HTTP API. Whatever the input, the answer must be a
 * 4xx in the standard error format (never a 5xx), and nothing may be written.
 */
class HostileHttpInputIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @Autowired
    private WalletService walletService;

    private String alice;
    private String bob;

    @BeforeEach
    void setUp() {
        alice = walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01711000001", WalletType.LIABILITY)).phone();
        bob = walletService.createWallet(new CreateWalletRequest(UUID.randomUUID(), "01711000002", WalletType.LIABILITY)).phone();
        topUp(alice, 50000, "fund");
    }

    private record Resp(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    private Resp call(String method, String uri, String key, String contentType, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + uri))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body());
    }

    private Resp post(String uri, String key, String body) throws Exception {
        return call("POST", uri, key, "application/json", body);
    }

    private Resp get(String uri) throws Exception {
        return call("GET", uri, null, null, null);
    }

    private String transferBody(String from, String to, String amount, String noteJson) {
        return """
                {"from_phone": "%s", "to_phone": "%s", "amount": %s%s}"""
                .formatted(from, to, amount, noteJson == null ? "" : ", \"note\": " + noteJson);
    }

    private void assertRejected(Resp response, String context) {
        assertTrue(response.status() >= 400 && response.status() < 500, context + " -> " + response);
        JsonNode body = response.json();
        assertTrue(body.has("error") && body.get("error").has("code"), context + " -> " + response);
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void malformedJsonBodiesAreClientErrorsAndWriteNothing() throws Exception {
        long before = count(Transactions.TABLE);
        List<String> bodies = List.of("", "{", "}", "null", "[]", "\"text\"", "123", "{\"from_phone\":}",
                "{\"from_phone\": \"" + alice + "\", \"from_phone\": \"" + bob + "\"}",
                "[".repeat(5000) + "]".repeat(5000), "{\"amount\": 1e999}", "\u0000", "{\"amount\": 99999999999999999999}");
        for (String body : bodies) {
            assertRejected(post("/api/v1/transfers", "k-" + UUID.randomUUID(), body), "transfer body " + body.length());
            assertRejected(post("/api/v1/wallets/add-money", "k-" + UUID.randomUUID(), body), "add-money body " + body.length());
            assertRejected(post("/api/v1/wallets", null, body), "create wallet body " + body.length());
        }
        assertEquals(before, count(Transactions.TABLE));
    }

    @Test
    void aNoteWithAnEmbeddedNulCharacterIsRejectedNotAServerError() throws Exception {
        Resp response = post("/api/v1/transfers", "k1", transferBody(alice, bob, "1000", "\"hi\\u0000there\""));

        assertRejected(response, "nul in note");
        assertEquals(1, count(Transactions.TABLE));
    }

    @Test
    void sqlAndUnicodeInTheNoteIsStoredVerbatim() throws Exception {
        String note = "Robert'); DROP TABLE wallets;-- é中文 💸 \"quoted\" \\ back";
        Resp response = post("/api/v1/transfers", "k1", transferBody(alice, bob, "1000", JSON.writeValueAsString(note)));

        assertEquals(201, response.status(), response.body());
        assertEquals(note, response.json().get("note").asString());
        assertEquals(4, count(Wallets.TABLE));
    }

    @Test
    void aNoteOfExactlyTheMaximumLengthWorksAndOneMoreIsRejected() throws Exception {
        int max = com.pathaopay.miniwallet.common.constant.AppConstants.NOTE_MAX_LENGTH;

        assertEquals(201, post("/api/v1/transfers", "k1",
                transferBody(alice, bob, "1000", "\"" + "n".repeat(max) + "\"")).status());
        assertRejected(post("/api/v1/transfers", "k2",
                transferBody(alice, bob, "1000", "\"" + "n".repeat(max + 1) + "\"")), "note too long");
        assertRejected(post("/api/v1/transfers", "k3",
                transferBody(alice, bob, "1000", "\"" + "n".repeat(5_000_000) + "\"")), "5 MB note");
    }

    @Test
    void hostilePhonesInThePathNeverReachTheDatabaseAsSql() throws Exception {
        long wallets = count(Wallets.TABLE);
        List<String> phones = List.of("01711000001'%20OR%20'1'='1", "%27%3B%20DROP%20TABLE%20wallets%3B--", "%00",
                "..%2F..%2Fetc%2Fpasswd", "0".repeat(10_000), "%F0%9F%92%A5", "01711000001%0d%0aX-Injected:%201",
                "+8801711000001", "880%2001711%20000001");
        for (String phone : phones) {
            Resp wallet = get("/api/v1/wallets/" + phone);
            Resp balance = get("/api/v1/wallets/" + phone + "/balance");
            Resp history = get("/api/v1/wallets/" + phone + "/transactions");
            for (Resp resp : List.of(wallet, balance, history)) {
                assertTrue(resp.status() < 500, phone.length() > 40 ? "long phone" : phone + " -> " + resp);
            }
        }
        assertEquals(wallets, count(Wallets.TABLE));
    }

    @Test
    void idempotencyKeyLengthBoundaries() throws Exception {
        String body = transferBody(alice, bob, "1000", null);

        assertEquals(201, post("/api/v1/transfers", "a".repeat(255), body).status());
        assertRejected(post("/api/v1/transfers", "a".repeat(256), body), "256-char key");
        assertRejected(post("/api/v1/transfers", " ", body), "blank key");
        assertRejected(post("/api/v1/transfers", null, body), "missing key");
        assertEquals(1, count(Transactions.TABLE) - 1);
    }

    @Test
    void oneKeyCannotBeUsedForTwoDifferentOperations() throws Exception {
        Resp transfer = post("/api/v1/transfers", "shared", transferBody(alice, bob, "1000", null));
        assertEquals(201, transfer.status());

        Resp addMoney = post("/api/v1/wallets/add-money", "shared", """
                {"phone": "%s", "amount": 1000, "gateway_ref": "%s", "payment_method": "VISA"}"""
                .formatted(alice, UUID.randomUUID()));

        assertEquals(422, addMoney.status(), addMoney.body());
        assertEquals("IDEMPOTENCY_KEY_REUSED", addMoney.json().get("error").get("code").asString());
    }

    @Test
    void numericEdgeCasesAreRejectedBeforeAnyMoneyMoves() throws Exception {
        for (String amount : List.of("0", "-1", "-9223372036854775808", "9223372036854775807", "9223372036854775806",
                "2147483648", "1", "99")) {
            Resp response = post("/api/v1/transfers", "k-" + amount, transferBody(alice, bob, amount, null));
            assertRejected(response, "transfer amount " + amount);
            Resp add = post("/api/v1/wallets/add-money", "a-" + amount, """
                    {"phone": "%s", "amount": %s, "gateway_ref": "%s", "payment_method": "VISA"}"""
                    .formatted(alice, amount, UUID.randomUUID()));
            assertRejected(add, "add-money amount " + amount);
        }
        assertEquals(50000, walletService.getBalance(alice).balance());
        assertEquals(0, walletService.getBalance(bob).balance());
    }

    @Test
    void addMoneyFieldAbuse() throws Exception {
        String ref = UUID.randomUUID().toString();
        for (String body : List.of(
                "{\"phone\": \"%s\", \"amount\": 1000, \"gateway_ref\": \"%s\", \"payment_method\": \"visa\"}".formatted(alice, ref),
                "{\"phone\": \"%s\", \"amount\": 1000, \"gateway_ref\": \"%s\", \"payment_method\": \"BITCOIN\"}".formatted(alice, ref),
                "{\"phone\": \"%s\", \"amount\": 1000, \"gateway_ref\": \"not-a-uuid\", \"payment_method\": \"VISA\"}".formatted(alice),
                "{\"phone\": \"%s\", \"amount\": 1000, \"gateway_ref\": null, \"payment_method\": \"VISA\"}".formatted(alice),
                "{\"phone\": [\"%s\"], \"amount\": 1000, \"gateway_ref\": \"%s\", \"payment_method\": \"VISA\"}".formatted(alice, ref),
                "{\"phone\": \"%s\", \"amount\": {\"$gt\": 0}, \"gateway_ref\": \"%s\", \"payment_method\": \"VISA\"}".formatted(alice, ref))) {
            assertRejected(post("/api/v1/wallets/add-money", "k-" + UUID.randomUUID(), body), body);
        }
    }

    @Test
    void transferringToYourselfInAnotherPhoneFormatIsStillRejected() throws Exception {
        for (String alias : List.of("+880" + alice.substring(1), alice.substring(1), "880" + alice.substring(1))) {
            Resp response = post("/api/v1/transfers", "k-" + alias, transferBody(alice, alias, "1000", null));

            assertEquals(422, response.status(), alias + " " + response.body());
            assertEquals("SAME_WALLET_TRANSFER", response.json().get("error").get("code").asString());
        }
        assertEquals(50000, walletService.getBalance(alice).balance());
    }

    @Test
    void historyPagingExtremes() throws Exception {
        String base = "/api/v1/wallets/" + alice + "/transactions";
        Resp last = get(base + "?page=2147483647&page_size=100");
        assertEquals(200, last.status(), last.body());
        assertEquals(0, last.json().get("items").size());

        for (String query : List.of("page=0", "page=-1", "page=abc", "page=1.5", "page_size=0", "page_size=101",
                "page_size=-5", "page_size=99999999999", "page=99999999999", "type=transfer_out",
                "from=garbage", "to=2020-13-45T00:00:00Z", "from=2020-01-01")) {
            Resp response = get(base + "?" + query);
            assertRejected(response, query);
        }
        assertEquals(200, get(base + "?from=2020-01-01T00:00:00%2B06:00&to=2999-01-01T00:00:00-03:00").status());
    }

    @Test
    void unsupportedMethodsAndRoutesGetTheStandardErrorFormat() throws Exception {
        assertRejected(call("DELETE", "/api/v1/wallets/" + alice, null, null, null), "DELETE wallet");
        assertRejected(call("PUT", "/api/v1/transfers", "k", "application/json", "{}"), "PUT transfers");
        assertRejected(call("PATCH", "/api/v1/wallets/" + alice, null, "application/json", "{}"), "PATCH wallet");
        assertRejected(call("POST", "/api/v1/transactions/" + UUID.randomUUID(), null, null, null), "POST transaction");
        assertRejected(get("/api/v1/nothing-here"), "unknown route");
        assertRejected(get("/api/v1/transactions/not-a-uuid"), "bad transaction id");
        assertRejected(get("/api/v1/transactions/" + UUID.randomUUID()), "unknown transaction");
        assertRejected(get("/api/v1/wallets/add-money/not-a-uuid"), "bad request id");
        assertRejected(get("/api/v1/wallets/add-money/" + UUID.randomUUID()), "unknown request id");
    }

    @Test
    void createWalletAbuse() throws Exception {
        UUID user = UUID.randomUUID();
        for (String body : List.of(
                "{\"user_id\": \"not-a-uuid\", \"phone\": \"01911000001\", \"type\": \"LIABILITY\"}",
                "{\"user_id\": \"%s\", \"phone\": \"01911000001\", \"type\": \"liability\"}".formatted(user),
                "{\"user_id\": \"%s\", \"phone\": \"01911000001\", \"type\": \"LIABILITY; DROP TABLE wallets\"}".formatted(user),
                "{\"user_id\": \"%s\", \"phone\": \"019110000011\", \"type\": \"LIABILITY\"}".formatted(user),
                "{\"user_id\": \"%s\", \"phone\": \"01911000001\\u0000\", \"type\": \"LIABILITY\"}".formatted(user),
                "{\"user_id\": \"%s\", \"phone\": \"  \", \"type\": \"LIABILITY\"}".formatted(user),
                "{\"user_id\": \"%s\", \"phone\": \"١٩١١٠٠٠٠٠١\", \"type\": \"LIABILITY\"}".formatted(user))) {
            assertRejected(post("/api/v1/wallets", null, body), body);
        }
        assertEquals(4, count(Wallets.TABLE));
    }
}
