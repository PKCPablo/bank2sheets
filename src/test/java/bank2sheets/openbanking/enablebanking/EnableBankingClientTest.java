package bank2sheets.openbanking.enablebanking;

import bank2sheets.Bank2SheetsException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.TestKeys;
import bank2sheets.config.EnableBankingConfig;
import bank2sheets.http.HttpExchange.Response;
import bank2sheets.model.Account;
import bank2sheets.model.Transaction;
import bank2sheets.openbanking.saltedge.FakeHttp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * EnableBankingClient tests against an in-memory fake HTTP backend and a fake
 * code receiver; no real credentials or network calls are involved.
 */
class EnableBankingClientTest {

    private static final String BASE = "https://api.enablebanking.com";
    private static final long NOW = 1_000_000_000L;
    private static final String STATE = "state-1";

    @Test
    void connectSendsBearerJwtAndReturnsAuthUrl() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/aspsps?country=ES&psu_type=personal&service=AIS", """
                {"aspsps": [
                  {"name": "Santander", "country": "ES", "maximum_consent_validity": 2592000},
                  {"name": "BBVA", "country": "ES", "maximum_consent_validity": 15552000}
                ]}
                """);
        http.stub("/auth", "{\"url\": \"https://auth.enablebanking.com/authorize?x=1\","
                + " \"authorization_id\": \"auth-1\"}");
        EnableBankingClient client = client(http, () -> "https://cb?code=c&state=" + STATE);

        String url = client.connect();

        assertEquals("https://auth.enablebanking.com/authorize?x=1", url);
        assertEquals(2, http.requests.size());

        FakeHttp.Request aspsps = http.requests.get(0);
        assertEquals("GET", aspsps.method());
        assertEquals(BASE + "/aspsps?country=ES&psu_type=personal&service=AIS", aspsps.url());

        FakeHttp.Request auth = http.requests.get(1);
        assertEquals("POST", auth.method());
        assertEquals(BASE + "/auth", auth.url());
        assertTrue(auth.headers().get("Authorization").startsWith("Bearer "));

        // JWT header keeps kid/alg/typ; claims carry iss/aud/iat/exp.
        String jwt = auth.headers().get("Authorization").substring("Bearer ".length());
        String[] parts = jwt.split("\\.");
        JsonObject header = JsonParser.parseString(new String(
                Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("app-1", header.get("kid").getAsString());
        assertEquals("RS256", header.get("alg").getAsString());
        assertEquals("JWT", header.get("typ").getAsString());
        JsonObject claims = JsonParser.parseString(new String(
                Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("enablebanking.com", claims.get("iss").getAsString());
        assertEquals("api.enablebanking.com", claims.get("aud").getAsString());
        assertEquals(NOW, claims.get("iat").getAsLong());
        assertEquals(NOW + 3600, claims.get("exp").getAsLong());

        JsonObject body = JsonParser.parseString(auth.body()).getAsJsonObject();
        // maximum_consent_validity is in seconds (15552000 = 180 days).
        String expectedValidUntil = Instant.ofEpochSecond(NOW).plusSeconds(15552000).toString();
        String validUntil = body.getAsJsonObject("access").get("valid_until").getAsString();
        assertEquals(expectedValidUntil, validUntil);
        assertDoesNotThrow(() -> Instant.parse(validUntil));
        assertEquals("BBVA", body.getAsJsonObject("aspsp").get("name").getAsString());
        assertEquals("ES", body.getAsJsonObject("aspsp").get("country").getAsString());
        assertEquals("http://127.0.0.1:8765/bank2sheets/callback", body.get("redirect_url").getAsString());
        assertEquals("personal", body.get("psu_type").getAsString());
        assertFalse(body.get("state").getAsString().isBlank());
    }

    @Test
    void connectFallsBackTo90DaysWhenValidityAbsent() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/aspsps?country=ES&psu_type=personal&service=AIS",
                "{\"aspsps\": [{\"name\": \"BBVA\", \"country\": \"ES\"}]}");
        http.stub("/auth", "{\"url\": \"https://auth.enablebanking.com/authorize?x=1\"}");
        EnableBankingClient client = client(http, () -> "");

        client.connect();

        JsonObject body = JsonParser.parseString(http.requests.get(1).body()).getAsJsonObject();
        String expectedValidUntil = Instant.ofEpochSecond(NOW).plusSeconds(90 * 86400).toString();
        assertEquals(expectedValidUntil, body.getAsJsonObject("access").get("valid_until").getAsString());
    }

    @Test
    void findConnectionIdRejectsStateMismatch() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/aspsps?country=ES&psu_type=personal&service=AIS",
                "{\"aspsps\": [{\"name\": \"BBVA\", \"country\": \"ES\"}]}");
        http.stub("/auth", "{\"url\": \"https://auth.enablebanking.com/authorize?x=1\"}");
        EnableBankingClient client = client(http, () -> "https://cb?code=c&state=other-state");
        client.connect();
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                client::findConnectionId);
        assertTrue(error.getMessage().contains("state mismatch"));
    }

    @Test
    void findConnectionIdExchangesCodeForSessionId() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/aspsps?country=ES&psu_type=personal&service=AIS",
                "{\"aspsps\": [{\"name\": \"BBVA\", \"country\": \"ES\"}]}");
        http.stub("/auth", "{\"url\": \"https://auth.enablebanking.com/authorize?x=1\"}");
        http.stub("/sessions", "{\"session_id\": \"sess-1\", \"status\": \"AUTHORIZED\"}");
        AtomicReference<String> state = new AtomicReference<>();
        EnableBankingClient client = client(http,
                () -> "https://cb?code=the-code&state=" + state.get());
        client.connect();

        // Echo the real state generated by connect() (request 0 = /aspsps, 1 = /auth).
        FakeHttp.Request auth = http.requests.get(1);
        state.set(JsonParser.parseString(auth.body()).getAsJsonObject().get("state").getAsString());

        assertEquals("sess-1", client.findConnectionId());

        FakeHttp.Request sessions = http.requests.get(2);
        assertEquals("POST", sessions.method());
        assertEquals(BASE + "/sessions", sessions.url());
        JsonObject body = JsonParser.parseString(sessions.body()).getAsJsonObject();
        assertEquals("the-code", body.get("code").getAsString());
    }

    @Test
    void isActiveTrueOnlyForAuthorized() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/sessions/sess-1", "{\"status\": \"AUTHORIZED\"}");
        assertTrue(client(http, () -> "").isActive("sess-1"));

        FakeHttp pending = new FakeHttp(BASE);
        pending.stub("/sessions/sess-1", "{\"status\": \"PENDING\"}");
        assertFalse(client(pending, () -> "").isActive("sess-1"));
    }

    @Test
    void isActiveFalseOn404() {
        FakeHttp http = new FakeHttp(BASE);
        http.responses.put("/sessions/sess-1", new Response(404, "{\"error\": \"gone\"}"));
        assertFalse(client(http, () -> "").isActive("sess-1"));
    }

    @Test
    void listAccountsMapsUuidAndDetailsNameAndToleratesDetailsFailure() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/sessions/sess-1", """
                {"status": "AUTHORIZED",
                 "accounts": ["uuid-1", "uuid-2"],
                 "accounts_data": [
                   {"uid": "uuid-1", "identification_hash": "h1"},
                   {"uid": "uuid-2", "identification_hash": "h2"}
                 ]}
                """);
        http.stub("/accounts/uuid-1/details", "{\"account_id\": \"uuid-1\", \"name\": \"Cuenta Nominas\"}");
        http.responses.put("/accounts/uuid-2/details", new Response(500, "{\"error\": \"boom\"}"));
        EnableBankingClient client = client(http, () -> "");
        client.setSessionId("sess-1");

        assertEquals(
                List.of(new Account("uuid-1", "Cuenta Nominas"), new Account("uuid-2", "")),
                client.listAccounts());
    }

    @Test
    void fetchTransactionsPaginatesAndNormalizesPayload() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-1", "transaction_id": "tid-1",
                   "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "1200.50", "currency": "EUR"},
                   "remittance_information": ["Nomina", "agosto"],
                   "booking_date": "2026-08-01"},
                  {"transaction_id": "tid-2", "credit_debit_indicator": "DBIT",
                   "transaction_amount": {"amount": "3.20", "currency": "EUR"},
                   "creditor": {"name": "Cafe Central"},
                   "booking_date": "2026-08-02T10:00:00+02:00"}
                ], "continuation_key": "page+2="}
                """);
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK&continuation_key=page%2B2%3D", """
                {"transactions": [
                  {"credit_debit_indicator": "DBIT",
                   "transaction_amount": {"amount": "9.99", "currency": "EUR"},
                   "note": "Suscripcion",
                   "value_date": "2026-08-03"}
                ]}
                """);
        EnableBankingClient client = client(http, () -> "");

        List<Transaction> transactions = client.fetchTransactions("uuid-1");

        assertEquals(3, transactions.size());
        assertEquals(new Transaction(refId("ref-1", "2026-08-01|1200.50|EUR|Nomina agosto|"),
                "Nomina agosto", new BigDecimal("1200.50"), "EUR", LocalDate.of(2026, 8, 1)),
                transactions.get(0));
        assertEquals(new Transaction(refId("tid-2", "2026-08-02|-3.20|EUR|Cafe Central|"),
                "Cafe Central", new BigDecimal("-3.20"), "EUR", LocalDate.of(2026, 8, 2)),
                transactions.get(1));
        Transaction third = transactions.get(2);
        assertEquals(new Transaction(hashId("2026-08-03|-9.99|EUR|Suscripcion|"), "Suscripcion",
                new BigDecimal("-9.99"), "EUR", LocalDate.of(2026, 8, 3)), third);
        assertEquals(2, http.requests.size());
        assertEquals(BASE + "/accounts/uuid-1/transactions?transaction_status=BOOK&continuation_key=page%2B2%3D",
                http.requests.get(1).url());
    }

    @Test
    void fetchTransactionsHashesIdWhenNoReferenceOrTransactionId() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "1.00", "currency": "EUR"},
                   "booking_date": "2026-08-01"}
                ]}
                """);
        EnableBankingClient client = client(http, () -> "");

        Transaction transaction = client.fetchTransactions("uuid-1").get(0);

        assertEquals(hashId("2026-08-01|1.00|EUR||"), transaction.id());
    }

    @Test
    void sameEntryReferenceWithDifferentAmountsYieldsDistinctIds() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "same-ref", "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "10.00", "currency": "EUR"},
                   "booking_date": "2026-08-01"},
                  {"entry_reference": "same-ref", "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "20.00", "currency": "EUR"},
                   "booking_date": "2026-08-01"}
                ]}
                """);
        EnableBankingClient client = client(http, () -> "");

        List<Transaction> transactions = client.fetchTransactions("uuid-1");

        assertEquals(2, transactions.size());
        assertTrue(transactions.get(0).id().startsWith("eb:same-ref:"));
        assertTrue(transactions.get(1).id().startsWith("eb:same-ref:"));
        assertFalse(transactions.get(0).id().equals(transactions.get(1).id()));
    }

    @Test
    void listAccountsRejectsSessionWithoutAccountsArray() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/sessions/sess-1", "{\"status\": \"AUTHORIZED\"}");
        EnableBankingClient client = client(http, () -> "");
        client.setSessionId("sess-1");

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                client::listAccounts);
        assertTrue(error.getMessage().contains("no accounts array"));
    }

    @Test
    void listAccountsFallsBackToProductWhenDetailsFail() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/sessions/sess-1", """
                {"status": "AUTHORIZED",
                 "accounts": ["uuid-1", "uuid-2"],
                 "accounts_data": [
                   {"uid": "uuid-1", "product": "Cuenta corriente"},
                   {"uid": "uuid-2", "product": "Cuenta ahorro"}
                 ]}
                """);
        http.responses.put("/accounts/uuid-1/details", new Response(500, "{\"error\": \"boom\"}"));
        http.responses.put("/accounts/uuid-2/details", new Response(500, "{\"error\": \"boom\"}"));
        EnableBankingClient client = client(http, () -> "");
        client.setSessionId("sess-1");

        assertEquals(
                List.of(new Account("uuid-1", "Cuenta corriente"), new Account("uuid-2", "Cuenta ahorro")),
                client.listAccounts());
    }

    @Test
    void bearerTokenIsRebuiltWhenNearExpiry() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/sessions/sess-1", "{\"status\": \"AUTHORIZED\"}");
        AtomicLong clock = new AtomicLong(NOW);
        EnableBankingClient client = new EnableBankingClient(config(), http, () -> "", clock::get);

        client.isActive("sess-1");
        clock.addAndGet(4000);
        client.isActive("sess-1");

        String first = http.requests.get(0).headers().get("Authorization");
        String second = http.requests.get(1).headers().get("Authorization");
        assertFalse(first.equals(second));
    }

    @Test
    void hashIdIsStableAcrossVolatileFieldsAndSensitiveToCoreFields() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"credit_debit_indicator": "DBIT",
                   "transaction_amount": {"amount": "5.00", "currency": "EUR"},
                   "note": "Cafe", "value_date": "2026-08-04",
                   "status": "BOOK", "balance_after_transaction": "100.00"}
                ]}
                """);
        EnableBankingClient client = client(http, () -> "");
        Transaction first = client.fetchTransactions("uuid-1").get(0);

        // Same core fields, different volatile fields -> same id.
        FakeHttp refetch = new FakeHttp(BASE);
        refetch.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"credit_debit_indicator": "DBIT",
                   "transaction_amount": {"amount": "5.00", "currency": "EUR"},
                   "note": "Cafe", "value_date": "2026-08-04",
                   "status": "PDNG", "balance_after_transaction": "42.00"}
                ]}
                """);
        Transaction second = client(refetch, () -> "").fetchTransactions("uuid-1").get(0);
        assertEquals(first.id(), second.id());

        // Different amount -> different id.
        FakeHttp changed = new FakeHttp(BASE);
        changed.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"credit_debit_indicator": "DBIT",
                   "transaction_amount": {"amount": "6.00", "currency": "EUR"},
                   "note": "Cafe", "value_date": "2026-08-04"}
                ]}
                """);
        Transaction third = client(changed, () -> "").fetchTransactions("uuid-1").get(0);
        assertTrue(third.id().startsWith("eb:sha256:"));
        assertFalse(third.id().equals(first.id()));
    }

    @Test
    void fetchTransactionsFailsWhenNoDatePresent() {
        FakeHttp http = new FakeHttp(BASE);
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-x",
                   "transaction_amount": {"amount": "1.00", "currency": "EUR"}}
                ]}
                """);
        EnableBankingClient client = client(http, () -> "");

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.fetchTransactions("uuid-1"));
        assertTrue(error.getMessage().contains("booking_date/value_date/transaction_date"));
    }

    @Test
    void nonSuccessStatusThrowsWithStatusMethodPathAndBody() {
        FakeHttp http = new FakeHttp(BASE);
        http.responses.put("/sessions/sess-1", new Response(401, "{\"error\": \"bad token\"}"));
        EnableBankingClient client = client(http, () -> "");

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.isActive("sess-1"));
        assertTrue(error.getMessage().contains("401"));
        assertTrue(error.getMessage().contains("GET"));
        assertTrue(error.getMessage().contains("/sessions/sess-1"));
        assertTrue(error.getMessage().contains("bad token"));
    }

    /** Mirrors the client's short hash: first 16 hex chars of the canonical-fields sha256. */
    private static String shortHash(String canonical) {
        return hashId(canonical).substring("eb:sha256:".length()).substring(0, 16);
    }

    /** Mirrors the client's reference-based id: {@code eb:<reference>:<16-hex short hash>}. */
    private static String refId(String reference, String canonical) {
        return "eb:" + reference + ":" + shortHash(canonical);
    }

    /** Mirrors the client's canonical fallback: {@code eb:sha256:} + sha256 hex of the fields. */
    private static String hashId(String canonical) {
        try {
            return "eb:sha256:" + HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EnableBankingClient client(FakeHttp http, AuthorizationCodeReceiver receiver) {
        return new EnableBankingClient(config(), http, receiver, () -> NOW);
    }

    private static EnableBankingConfig config() {
        return new EnableBankingConfig("app-1", TestKeys.base64Pem(), "http://127.0.0.1:8765/bank2sheets/callback");
    }

}
