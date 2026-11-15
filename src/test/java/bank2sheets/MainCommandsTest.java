package bank2sheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.CliTestSupport.Result;
import bank2sheets.config.AccountConfig;
import bank2sheets.config.AppConfig;
import bank2sheets.http.FakeHttp;
import bank2sheets.http.HttpExchange.Response;
import bank2sheets.openbanking.enablebanking.AuthorizationCodeReceiver;
import bank2sheets.openbanking.enablebanking.EnableBankingClient;
import bank2sheets.sheets.GoogleSheetsClient;
import bank2sheets.sheets.SheetsTestSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end command tests: connect/fetch/sync driven through Main with a fake
 * HTTP backend and a temp runtime state file. No real credentials or network
 * calls.
 */
class MainCommandsTest {

    /** Header row stub body shared by the sync tests. */
    private static final String HEADER_JSON =
            "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\",\"Cuenta\"]]}";

    /** Single NOMINA transaction payload shared by the sync/fetch tests. */
    private static final String NOMINA_TRANSACTION_JSON = """
            {"transactions": [
              {"entry_reference": "ref-1", "credit_debit_indicator": "CRDT",
               "transaction_amount": {"amount": "1200.50", "currency": "EUR"},
               "remittance_information": ["Nomina"],
               "booking_date": "2026-08-01"}
            ]}
            """;

    @TempDir
    Path tempDir;

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
    void enableBankingConnectUsesConfiguredAspsp() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.stub("/aspsps?country=ES&psu_type=personal&service=AIS",
                "{\"aspsps\": [{\"name\": \"Santander\", \"country\": \"ES\","
                        + " \"maximum_consent_validity\": 2592000}]}");
        http.stub("/auth", "{\"url\": \"https://auth.enablebanking.com/authorize?x=1\"}");
        http.stub("/sessions", "{\"session_id\": \"sess-eb-1\"}");
        http.stub("/sessions/sess-eb-1", "{\"status\": \"AUTHORIZED\", \"accounts\": []}");
        Path stateFile = tempDir.resolve("state.properties");
        AppConfig santander = new AppConfig("app-1", TestKeys.base64Pem(),
                "http://127.0.0.1:8765/bank2sheets/callback", "", "Santander", "ES", "personal",
                List.of(new AccountConfig("enablebanking", "uuid-1")));

        Result result = runEnableBanking(http, stateFile, santander, "connect");

        assertEquals(0, result.exitCode());
        assertEquals(EB_BASE + "/aspsps?country=ES&psu_type=personal&service=AIS",
                http.requests.get(0).url());
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
        AppConfig noAccounts = new AppConfig("app-1", TestKeys.base64Pem(),
                "http://127.0.0.1:8765/bank2sheets/callback", "", List.of());

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
        http.stub("/sessions/sess-eb-1", "{\"status\": \"AUTHORIZED\", \"accounts\": [\"uuid-1\"]}");
        http.stub("/accounts/uuid-1/details", "{\"name\": \"Cuenta Nominas\"}");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubReadySheet(sheets);
        sheets.stub("/sheet-1/values/E2:E", "{}");
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "enablebanking.session_id=sess-eb-1\n");
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = new Main(c -> enableBankingClient(cfg, http), () -> cfg,
                () -> new GoogleSheetsClient(SheetsTestSupport.config(), sheets), stateFile, () -> { });

        Result first = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, first.exitCode());
        assertTrue(first.stdout().contains("appended 1 new row(s)"));
        String appendBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith(":append?valueInputOption=RAW"))
                .findFirst().orElseThrow().body();
        assertEquals("Cuenta Nominas", JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(5).getAsString());

        // Mark the appended TransactionId as already present, then sync again.
        String transactionId = JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(4).getAsString();
        sheets.stub("/sheet-1/values/E2:E", "{\"values\": [[\"" + transactionId + "\"]]}");

        Result second = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("appended 0 new row(s), skipped 1 duplicate(s)"));
    }

    @Test
    void enableBankingSyncLabelsRowsPerAccountAndStaysIdempotent() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.stub("/sessions/sess-eb-1", "{\"status\": \"AUTHORIZED\", \"accounts\": [\"uuid-1\", \"uuid-2\"]}");
        http.stub("/accounts/uuid-1/details", "{\"name\": \"Cuenta Nominas\"}");
        http.stub("/accounts/uuid-2/details", "{\"name\": \"Cuenta Ahorro\"}");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        http.stub("/accounts/uuid-2/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-2", "credit_debit_indicator": "DRDT",
                   "transaction_amount": {"amount": "30.10", "currency": "EUR"},
                   "remittance_information": ["Compra"],
                   "booking_date": "2026-08-02"}
                ]}
                """);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubReadySheet(sheets);
        sheets.stub("/sheet-1/values/E2:E", "{}");
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "enablebanking.session_id=sess-eb-1\n");
        AppConfig cfg = new AppConfig("app-1", TestKeys.base64Pem(),
                "http://127.0.0.1:8765/bank2sheets/callback", "sess-eb-1",
                List.of(new AccountConfig("enablebanking", "uuid-1"),
                        new AccountConfig("enablebanking", "uuid-2")));
        Main main = new Main(c -> enableBankingClient(cfg, http), () -> cfg,
                () -> new GoogleSheetsClient(SheetsTestSupport.config(), sheets), stateFile, () -> { });

        Result first = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, first.exitCode());
        assertTrue(first.stdout().contains("appended 2 new row(s)"));
        List<String> appendBodies = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith(":append?valueInputOption=RAW"))
                .map(r -> r.body()).toList();
        JsonArray values = JsonParser.parseString(appendBodies.get(0)).getAsJsonObject()
                .getAsJsonArray("values");
        assertEquals(2, values.size());
        assertEquals("Cuenta Nominas", values.get(0).getAsJsonArray().get(5).getAsString());
        assertEquals("Cuenta Ahorro", values.get(1).getAsJsonArray().get(5).getAsString());

        // Mark both appended TransactionIds as already present, then sync again.
        String firstId = values.get(0).getAsJsonArray().get(4).getAsString();
        String secondId = values.get(1).getAsJsonArray().get(4).getAsString();
        sheets.stub("/sheet-1/values/E2:E",
                "{\"values\": [[\"" + firstId + "\"], [\"" + secondId + "\"]]}");

        Result second = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("appended 0 new row(s), skipped 2 duplicate(s)"));
    }

    @Test
    void enableBankingSyncFallsBackToAccountIdWhenNameBlank() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.stub("/sessions/sess-eb-1", "{\"status\": \"AUTHORIZED\", \"accounts\": [\"uuid-1\"]}");
        http.stub("/accounts/uuid-1/details", "{\"name\": \"\"}");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubReadySheet(sheets);
        sheets.stub("/sheet-1/values/E2:E", "{}");
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "enablebanking.session_id=sess-eb-1\n");
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = new Main(c -> enableBankingClient(cfg, http), () -> cfg,
                () -> new GoogleSheetsClient(SheetsTestSupport.config(), sheets), stateFile, () -> { });

        Result result = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));

        assertEquals(0, result.exitCode());
        String appendBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith(":append?valueInputOption=RAW"))
                .findFirst().orElseThrow().body();
        assertEquals("uuid-1", JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(5).getAsString());
    }

    @Test
    void enableBankingConnectWithoutCredentialsExitsNonZero() {
        FakeHttp http = new FakeHttp(EB_BASE);
        Path stateFile = tempDir.resolve("state.properties");
        AppConfig noCredentials = new AppConfig("", "", "", "",
                List.of(new AccountConfig("enablebanking", "uuid-1")));

        Result result = runEnableBanking(http, stateFile, noCredentials, "connect");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("ENABLE_BANKING_APP_ID"));
    }

    @Test
    void fetchWithoutConnectionExitsNonZero() {
        FakeHttp http = new FakeHttp(EB_BASE);
        Path stateFile = tempDir.resolve("missing.properties");

        Result result = runEnableBanking(http, stateFile, enableBankingConfig(""), "fetch");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("run `connect` once"));
        assertTrue(result.stderr().contains("ENABLE_BANKING_SESSION_ID"));
    }

    @Test
    void fetchWithoutAccountsExitsNonZero() {
        FakeHttp http = new FakeHttp(EB_BASE);
        Path stateFile = tempDir.resolve("state.properties");
        AppConfig noAccounts = new AppConfig("app-1", TestKeys.base64Pem(),
                "http://127.0.0.1:8765/bank2sheets/callback", "", List.of());

        Result result = runEnableBanking(http, stateFile, noAccounts, "fetch");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("no accounts configured"));
    }

    @Test
    void fetchIgnoresLegacySaltEdgeStateKey() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.stub("/sessions/sess-eb-1", """
                {"status": "AUTHORIZED", "accounts": ["uuid-1"]}
                """);
        http.stub("/accounts/uuid-1/details", "{\"name\": \"Cuenta Nominas\"}");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "saltedge.connection_id=conn-1\nenablebanking.session_id=sess-eb-1\n");

        Result result = runEnableBanking(http, stateFile, enableBankingConfig(""), "fetch");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Account uuid-1 (Cuenta Nominas): 1 transaction(s)"));
    }

    @Test
    void syncWithoutSheetsConfigExitsNonZero() {
        FakeHttp http = new FakeHttp(EB_BASE);
        Path stateFile = tempDir.resolve("state.properties");

        Result result = CliTestSupport.capture(() -> new Main(c -> enableBankingClient(c, http),
                () -> enableBankingConfig("sess-eb-1"),
                () -> GoogleSheetsClient.create(new bank2sheets.config.SheetsConfig("", "")),
                stateFile, () -> { })
                .dispatch(new String[] {"sync"}));

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("GOOGLE_SERVICE_ACCOUNT_B64"));
    }

    @Test
    void syncWithSheetsErrorExitsNonZero() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubToken(sheets);
        sheets.stub("/sheet-1/values/E2:E", 403, "{\"error\":{\"message\":\"permission denied\"}}");
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "enablebanking.session_id=sess-eb-1\n");

        Result result = runEnableBanking(http, sheets, stateFile, enableBankingConfig(""), "sync");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Google Sheets read failed"));
    }

    @Test
    void syncWithoutConnectionExitsNonZero() {
        FakeHttp http = new FakeHttp(EB_BASE);
        Path stateFile = tempDir.resolve("missing.properties");

        Result result = runEnableBanking(http, stateFile, enableBankingConfig(""), "sync");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("run `connect` once"));
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
        return new AppConfig("app-1", TestKeys.base64Pem(),
                "http://127.0.0.1:8765/bank2sheets/callback", sessionId,
                List.of(new AccountConfig("enablebanking", "uuid-1")));
    }

    private static Result runEnableBanking(FakeHttp http, Path stateFile, AppConfig config, String... args) {
        return runEnableBanking(http, SheetsTestSupport.fakeHttp(), stateFile, config, args);
    }

    private static Result runEnableBanking(FakeHttp http, FakeHttp sheets, Path stateFile,
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

    /** Stubs a Sheets backend with a valid token, the header row, and a successful append. */
    private static void stubReadySheet(FakeHttp sheets) {
        SheetsTestSupport.stubToken(sheets);
        sheets.stub("/sheet-1/values/A1:F1", HEADER_JSON);
        sheets.stub("/sheet-1/values/A1:F:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
    }
}
