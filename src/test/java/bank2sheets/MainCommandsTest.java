package bank2sheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.CliTestSupport.Result;
import bank2sheets.config.AccountConfig;
import bank2sheets.config.AppConfig;
import bank2sheets.config.SheetsConfig;
import bank2sheets.http.HttpExchange.Response;
import bank2sheets.openbanking.enablebanking.AuthorizationCodeReceiver;
import bank2sheets.openbanking.enablebanking.EnableBankingClient;
import bank2sheets.openbanking.saltedge.FakeHttp;
import bank2sheets.openbanking.saltedge.SaltEdgeClient;
import bank2sheets.sheets.FakeSheetsHttp;
import bank2sheets.sheets.GoogleSheetsClient;
import bank2sheets.sheets.SheetsTestSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end command tests: connect/fetch/sync driven through Main with a fake
 * HTTP backend and a temp runtime state file. No real credentials or network
 * calls.
 */
class MainCommandsTest {

    @TempDir
    Path tempDir;

    @Test
    void connectEndToEndPrintsUrlAndPersistsConnectionId() throws IOException {
        FakeHttp http = new FakeHttp();
        http.stub("/connections/connect",
                """
                {"data": {"connect_url": "https://www.saltedge.com/connect?token=t1"}}
                """);
        http.stub("/connections?customer_id=cust-1",
                """
                {"data": [
                  {"id": "conn-1", "status": "active", "created_at": "2026-08-04T10:00:00Z"}
                ]}
                """);
        http.stub("/connections/conn-1", "{\"data\": {\"id\": \"conn-1\", \"status\": \"active\"}}");
        http.stub("/accounts?connection_id=conn-1", "{\"data\": []}");
        Path stateFile = tempDir.resolve("state.properties");

        Result result = runCli(http, stateFile, config(""), "connect");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("https://www.saltedge.com/connect?token=t1"));
        assertTrue(Files.readString(stateFile).contains("saltedge.connection_id=conn-1"));
    }

    @Test
    void connectWithPendingConnectionDoesNotPersistState() throws IOException {
        FakeHttp http = new FakeHttp();
        http.stub("/connections/connect",
                """
                {"data": {"connect_url": "https://www.saltedge.com/connect?token=t1"}}
                """);
        http.stub("/connections?customer_id=cust-1",
                """
                {"data": [
                  {"id": "conn-1", "status": "pending", "created_at": "2026-08-04T10:00:00Z"}
                ]}
                """);
        http.stub("/connections/conn-1", "{\"data\": {\"id\": \"conn-1\", \"status\": \"pending\"}}");
        http.stub("/accounts?connection_id=conn-1", "{\"data\": []}");
        Path stateFile = tempDir.resolve("state.properties");

        Result result = runCli(http, stateFile, config(""), "connect");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("not active"));
        assertFalse(Files.exists(stateFile));
    }

    @Test
    void fetchEndToEndReadsStateAndPrintsTransactions() throws IOException {
        FakeHttp http = new FakeHttp();
        http.stub("/accounts?connection_id=conn-1",
                """
                {"data": [{"id": "acct-1", "name": "Cuenta BBVA"}]}
                """);
        http.stub("/transactions?connection_id=conn-1&account_id=acct-1",
                // ponytail: the repeated transaction JSON payloads across these tests
                // could be a shared fixture constant.
                """
                {"data": [
                  {"id": "t1", "description": "Nomina", "amount": 1200.50, "currency_code": "EUR",
                   "made_on": "2026-08-01T10:30:00Z"},
                  {"id": "t2", "description": "Cafe", "amount": "-3.20", "currency_code": "EUR",
                   "made_on": "2026-08-02"}
                ]}
                """);
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "saltedge.connection_id=conn-1\n");

        Result result = runCli(http, stateFile, config(""), "fetch");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Account acct-1 (Cuenta BBVA): 2 transaction(s)"));
        assertTrue(result.stdout().contains(
                "{\"id\":\"t1\",\"description\":\"Nomina\",\"amount\":1200.50,\"currency\":\"EUR\","
                        + "\"madeOn\":\"2026-08-01\"}"));
        assertTrue(result.stdout().contains(
                "{\"id\":\"t2\",\"description\":\"Cafe\",\"amount\":-3.20,\"currency\":\"EUR\","
                        + "\"madeOn\":\"2026-08-02\"}"));
    }

    @Test
    void fetchWithoutConnectionExitsNonZero() {
        FakeHttp http = new FakeHttp();
        Path stateFile = tempDir.resolve("missing.properties");

        Result result = runCli(http, stateFile, config(""), "fetch");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("run `connect` once"));
    }

    @Test
    void fetchWithoutAccountsExitsNonZero() {
        FakeHttp http = new FakeHttp();
        Path stateFile = tempDir.resolve("state.properties");
        AppConfig noAccounts = new AppConfig("app-1", "secret-1", "cust-1", "conn-1", List.of());

        Result result = runCli(http, stateFile, noAccounts, "fetch");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("no accounts configured"));
    }

    @Test
    void connectWithoutCredentialsExitsNonZero() {
        FakeHttp http = new FakeHttp();
        Path stateFile = tempDir.resolve("state.properties");
        AppConfig noCredentials = new AppConfig("", "", "cust-1", "",
                List.of(new AccountConfig("saltedge", "acct-1")));

        // The injected factory bypasses ProviderSources, so it mirrors the saltedge
        // credential guard that ProviderSources applies before building the client.
        Result result = CliTestSupport.capture(() -> new Main(c -> {
            if (c.saltEdgeAppId().isBlank() || c.saltEdgeSecret().isBlank()) {
                throw new Bank2SheetsException(
                        "SALTEDGE_APP_ID and SALTEDGE_SECRET must be set in the environment");
            }
            return new SaltEdgeClient(c, http);
        }, () -> noCredentials, () -> new GoogleSheetsClient(SheetsTestSupport.config(), new FakeSheetsHttp()),
                stateFile, () -> { })
                .dispatch(new String[] {"connect"}));

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("SALTEDGE_APP_ID and SALTEDGE_SECRET"));
    }

    @Test
    void syncEndToEndAppendsOnlyNewTransactions() throws IOException {
        FakeHttp http = new FakeHttp();
        http.stub("/transactions?connection_id=conn-1&account_id=acct-1",
                """
                {"data": [
                  {"id": "t1", "description": "Nomina", "amount": 1200.50, "currency_code": "EUR",
                   "made_on": "2026-08-01T10:30:00Z"},
                  {"id": "t2", "description": "Cafe", "amount": "-3.20", "currency_code": "EUR",
                   "made_on": "2026-08-02"}
                ]}
                """);
        FakeSheetsHttp sheets = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(sheets);
        sheets.stub("/sheet-1/values/E2:E", "{\"values\":[[\"t1\"]]}");
        sheets.stub("/sheet-1/values/A1:E1",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\"]]}");
        sheets.stub("/sheet-1/values/A1:E:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "saltedge.connection_id=conn-1\n");

        Result result = runCli(http, sheets, stateFile, config("conn-1"), "sync");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "sync: fetched 2 transaction(s), appended 1 new row(s), skipped 1 duplicate(s)"));
        List<FakeHttp.Request> appends = sheets.requests().stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith(":append?valueInputOption=RAW"))
                .toList();
        assertEquals(1, appends.size());
        String body = appends.get(0).body();
        assertFalse(body.contains("\"t1\""));
        JsonArray values = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("values");
        assertEquals(1, values.size());
        JsonArray row = values.get(0).getAsJsonArray();
        assertEquals("2026-08-02", row.get(0).getAsString());
        assertEquals("Cafe", row.get(1).getAsString());
        assertEquals(new BigDecimal("-3.20"), row.get(2).getAsBigDecimal());
        assertEquals("EUR", row.get(3).getAsString());
        assertEquals("t2", row.get(4).getAsString());
    }

    @Test
    void syncIsIdempotentWhenAllTransactionsAreAlreadyPresent() throws IOException {
        FakeHttp http = new FakeHttp();
        http.stub("/transactions?connection_id=conn-1&account_id=acct-1",
                """
                {"data": [
                  {"id": "t1", "description": "Nomina", "amount": 1200.50, "currency_code": "EUR",
                   "made_on": "2026-08-01T10:30:00Z"},
                  {"id": "t2", "description": "Cafe", "amount": "-3.20", "currency_code": "EUR",
                   "made_on": "2026-08-02"}
                ]}
                """);
        FakeSheetsHttp sheets = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(sheets);
        sheets.stub("/sheet-1/values/E2:E", "{\"values\":[[\"t1\"],[\"t2\"]]}");
        sheets.stub("/sheet-1/values/A1:E1",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\"]]}");
        sheets.stub("/sheet-1/values/A1:E:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "saltedge.connection_id=conn-1\n");

        Result result = runCli(http, sheets, stateFile, config("conn-1"), "sync");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "sync: fetched 2 transaction(s), appended 0 new row(s), skipped 2 duplicate(s)"));
        assertTrue(sheets.requests().stream()
                .noneMatch(r -> "POST".equals(r.method()) && r.url().endsWith(":append")));
    }

    @Test
    void syncWithoutSheetsConfigExitsNonZero() {
        FakeHttp http = new FakeHttp();
        Path stateFile = tempDir.resolve("state.properties");

        Result result = CliTestSupport.capture(() -> new Main(c -> new SaltEdgeClient(c, http), () -> config("conn-1"),
                () -> GoogleSheetsClient.create(new SheetsConfig("", "")), stateFile, () -> { })
                .dispatch(new String[] {"sync"}));

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("GOOGLE_SERVICE_ACCOUNT_B64"));
    }

    @Test
    void syncWithSaltEdgeErrorExitsNonZero() throws IOException {
        FakeHttp http = new FakeHttp();
        FakeSheetsHttp sheets = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(sheets);
        sheets.stub("/sheet-1/values/E2:E", "{}");
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "saltedge.connection_id=conn-1\n");

        Result result = runCli(http, sheets, stateFile, config("conn-1"), "sync");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Salt Edge request failed"));
    }

    @Test
    void syncWithSheetsErrorExitsNonZero() throws IOException {
        FakeHttp http = new FakeHttp();
        http.stub("/transactions?connection_id=conn-1&account_id=acct-1",
                """
                {"data": [{"id": "t1", "description": "Nomina", "amount": 1200.50, "currency_code": "EUR",
                   "made_on": "2026-08-01T10:30:00Z"}]}
                """);
        FakeSheetsHttp sheets = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(sheets);
        sheets.stub("/sheet-1/values/E2:E", 403, "{\"error\":{\"message\":\"permission denied\"}}");
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "saltedge.connection_id=conn-1\n");

        Result result = runCli(http, sheets, stateFile, config("conn-1"), "sync");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Google Sheets read failed"));
    }

    @Test
    void syncWithoutConnectionExitsNonZero() {
        FakeHttp http = new FakeHttp();
        Path stateFile = tempDir.resolve("missing.properties");

        Result result = runCli(http, stateFile, config(""), "sync");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("run `connect` once"));
    }

    private static AppConfig config(String connectionId) {
        return new AppConfig("app-1", "secret-1", "cust-1", connectionId,
                List.of(new AccountConfig("saltedge", "acct-1")));
    }

    // ---- Enable Banking command tests ----

    private static final String EB_BASE = "https://api.enablebanking.com";
    private static final long FIXED_NOW = 1_000_000_000L;

    @Test
    void enableBankingConnectPersistsSessionId() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.stub("/aspsps?country=ES&psu_type=personal&service=AIS",
                "{\"aspsps\": [{\"name\": \"BBVA\", \"country\": \"ES\","
                        + " \"maximum_consent_validity\": 15552000}]}");
        http.stub("/auth", "{\"url\": \"https://auth.enablebanking.com/authorize?x=1\"}");
        http.stub("/sessions", "{\"session_id\": \"sess-eb-1\"}");
        http.stub("/sessions/sess-eb-1", "{\"status\": \"AUTHORIZED\", \"accounts\": []}");
        Path stateFile = tempDir.resolve("state.properties");

        Result result = runEnableBanking(http, stateFile, enableBankingConfig(""), "connect");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("https://auth.enablebanking.com/authorize?x=1"));
        assertTrue(Files.readString(stateFile).contains("enablebanking.session_id=sess-eb-1"));
    }

    @Test
    void enableBankingConnectWithoutAccountsListsAvailableAccounts() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.stub("/aspsps?country=ES&psu_type=personal&service=AIS",
                "{\"aspsps\": [{\"name\": \"BBVA\", \"country\": \"ES\","
                        + " \"maximum_consent_validity\": 15552000}]}");
        http.stub("/auth", "{\"url\": \"https://auth.enablebanking.com/authorize?x=1\"}");
        http.stub("/sessions", "{\"session_id\": \"sess-eb-1\"}");
        http.stub("/sessions/sess-eb-1", "{\"status\": \"AUTHORIZED\", \"accounts\": [\"uuid-1\"]}");
        http.stub("/accounts/uuid-1/details", "{\"name\": \"Cuenta Nominas\"}");
        Path stateFile = tempDir.resolve("state.properties");
        AppConfig noAccounts = new AppConfig("", "", "", "",
                "app-1", base64Pem(), "http://127.0.0.1:8765/bank2sheets/callback", "", List.of());

        Result result = runEnableBanking(http, stateFile, noAccounts, "connect");

        assertEquals(0, result.exitCode());
        assertTrue(Files.readString(stateFile).contains("enablebanking.session_id=sess-eb-1"));
        assertTrue(result.stdout().contains("Available accounts:"));
        assertTrue(result.stdout().contains("uuid-1 (Cuenta Nominas)"));
        assertTrue(result.stdout().contains("Set BANK2SHEETS_ACCOUNTS=enablebanking:<accountId>"));
    }

    @Test
    void enableBankingFetchPrintsNormalizedTransactions() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.stub("/sessions/sess-eb-1", """
                {"status": "AUTHORIZED", "accounts": ["uuid-1"]}
                """);
        http.stub("/accounts/uuid-1/details", "{\"name\": \"Cuenta Nominas\"}");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-1", "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "1200.50", "currency": "EUR"},
                   "remittance_information": ["Nomina", "agosto"],
                   "booking_date": "2026-08-01"}
                ]}
                """);
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "enablebanking.session_id=sess-eb-1\n");

        Result result = runEnableBanking(http, stateFile, enableBankingConfig(""), "fetch");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Account uuid-1 (Cuenta Nominas): 1 transaction(s)"));
        assertTrue(result.stdout().contains("\"id\":\"eb:ref-1:"));
        assertTrue(result.stdout().contains(
                "\"description\":\"Nomina agosto\",\"amount\":1200.50,\"currency\":\"EUR\","
                        + "\"madeOn\":\"2026-08-01\"}"));
    }

    @Test
    void enableBankingSyncAppendsThenIsIdempotent() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-1", "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "1200.50", "currency": "EUR"},
                   "remittance_information": ["Nomina"],
                   "booking_date": "2026-08-01"}
                ]}
                """);
        FakeSheetsHttp sheets = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(sheets);
        sheets.stub("/sheet-1/values/E2:E", "{}");
        sheets.stub("/sheet-1/values/A1:E1",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\"]]}");
        sheets.stub("/sheet-1/values/A1:E:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "enablebanking.session_id=sess-eb-1\n");
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = new Main(c -> enableBankingClient(cfg, http), () -> cfg,
                () -> new GoogleSheetsClient(SheetsTestSupport.config(), sheets), stateFile, () -> { });

        Result first = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, first.exitCode());
        assertTrue(first.stdout().contains("appended 1 new row(s)"));

        // Mark the appended TransactionId as already present, then sync again.
        String appendBody = sheets.requests().stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith(":append?valueInputOption=RAW"))
                .findFirst().orElseThrow().body();
        String transactionId = JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(4).getAsString();
        sheets.stub("/sheet-1/values/E2:E", "{\"values\": [[\"" + transactionId + "\"]]}");

        Result second = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("appended 0 new row(s), skipped 1 duplicate(s)"));
    }

    @Test
    void enableBankingConnectWithoutCredentialsExitsNonZero() {
        FakeHttp http = new FakeHttp(EB_BASE);
        Path stateFile = tempDir.resolve("state.properties");
        AppConfig noCredentials = new AppConfig("", "", "", "", "", "", "", "",
                List.of(new AccountConfig("enablebanking", "uuid-1")));

        Result result = runEnableBanking(http, stateFile, noCredentials, "connect");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("ENABLE_BANKING_APP_ID"));
    }

    @Test
    void enableBankingFetchWithoutSessionExitsNonZero() {
        FakeHttp http = new FakeHttp(EB_BASE);
        Path stateFile = tempDir.resolve("missing.properties");

        Result result = runEnableBanking(http, stateFile, enableBankingConfig(""), "fetch");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("ENABLE_BANKING_SESSION_ID"));
    }

    @Test
    void enableBankingFetchWithProviderErrorExitsNonZero() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.responses.put("/accounts/uuid-1/transactions?transaction_status=BOOK",
                new Response(500, "{\"error\": \"boom\"}"));
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "enablebanking.session_id=sess-eb-1\n");

        Result result = runEnableBanking(http, stateFile, enableBankingConfig(""), "fetch");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Enable Banking request failed"));
    }

    /** Enable Banking config with valid credentials and the given session id (blank = unset). */
    private static AppConfig enableBankingConfig(String sessionId) {
        return new AppConfig("", "", "", "",
                "app-1", base64Pem(), "http://127.0.0.1:8765/bank2sheets/callback", sessionId,
                List.of(new AccountConfig("enablebanking", "uuid-1")));
    }

    private static Result runEnableBanking(FakeHttp http, Path stateFile, AppConfig config, String... args) {
        return runEnableBanking(http, new FakeSheetsHttp(), stateFile, config, args);
    }

    private static Result runEnableBanking(FakeHttp http, FakeSheetsHttp sheets, Path stateFile,
            AppConfig config, String... args) {
        return CliTestSupport.capture(() -> new Main(c -> enableBankingClient(c, http), () -> config,
                () -> new GoogleSheetsClient(SheetsTestSupport.config(), sheets), stateFile, () -> { })
                .dispatch(args));
    }

    /** Builds the Enable Banking client with a fake HTTP backend and a state-echoing receiver. */
    private static EnableBankingClient enableBankingClient(AppConfig config, FakeHttp http) {
        EnableBankingClient client = new EnableBankingClient(config.enableBankingConfig(), http,
                echoStateReceiver(http), () -> FIXED_NOW);
        if (!config.enableBankingSessionId().isBlank()) {
            client.setSessionId(config.enableBankingSessionId());
        }
        return client;
    }

    /** Receiver that echoes the real state generated by connect() from the recorded /auth request. */
    private static AuthorizationCodeReceiver echoStateReceiver(FakeHttp http) {
        return () -> {
            String authBody = http.requests.stream().filter(r -> r.url().endsWith("/auth"))
                    .findFirst().orElseThrow().body();
            String state = JsonParser.parseString(authBody).getAsJsonObject().get("state").getAsString();
            return "https://cb?code=the-code&state=" + state;
        };
    }

    /** Generates a throwaway RSA key pair and returns its base64 PKCS#8 PEM. */
    private static String base64Pem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                            .encodeToString(keys.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----";
            return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Result runCli(FakeHttp http, Path stateFile, AppConfig config, String... args) {
        return runCli(http, new FakeSheetsHttp(), stateFile, config, args);
    }

    private static Result runCli(FakeHttp http, FakeSheetsHttp sheets, Path stateFile, AppConfig config,
            String... args) {
        return CliTestSupport.capture(() -> new Main(c -> new SaltEdgeClient(c, http), () -> config,
                () -> new GoogleSheetsClient(SheetsTestSupport.config(), sheets), stateFile, () -> { })
                .dispatch(args));
    }
}
