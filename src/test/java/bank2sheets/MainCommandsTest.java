package bank2sheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        stubSession(http, "[]");
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
        stubSession(http, "[]");
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
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
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
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-1", "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "1200.50", "currency": "EUR"},
                   "remittance_information": ["Nomina", "agosto"],
                   "booking_date": "2026-08-01"}
                ]}
                """);
        Path stateFile = sessionState();

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
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "2026-08");
        stubMonthSheet(sheets, "2026-08");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = mainFor(cfg, http, sheets, stateFile);

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
        sheets.stub("/sheet-1/values/'2026-08'!E2:E", "{\"values\": [[\"" + transactionId + "\"]]}");

        Result second = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("appended 0 new row(s), skipped 1 duplicate(s)"));
    }

    @Test
    void enableBankingSyncLabelsRowsPerAccountAndStaysIdempotent() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        stubSession(http, "[\"uuid-1\", \"uuid-2\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        stubAccountDetails(http, "uuid-2", "Cuenta Ahorro");
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
        stubSpreadsheetTitles(sheets, "2026-08");
        stubMonthSheet(sheets, "2026-08");
        Path stateFile = sessionState();
        AppConfig cfg = new AppConfig("app-1", TestKeys.base64Pem(),
                "http://127.0.0.1:8765/bank2sheets/callback", "sess-eb-1",
                List.of(new AccountConfig("enablebanking", "uuid-1"),
                        new AccountConfig("enablebanking", "uuid-2")));
        Main main = mainFor(cfg, http, sheets, stateFile);

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
        sheets.stub("/sheet-1/values/'2026-08'!E2:E",
                "{\"values\": [[\"" + firstId + "\"], [\"" + secondId + "\"]]}");

        Result second = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("appended 0 new row(s), skipped 2 duplicate(s)"));
    }

    @Test
    void enableBankingSyncFallsBackToAccountIdWhenNameBlank() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "2026-08");
        stubMonthSheet(sheets, "2026-08");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = mainFor(cfg, http, sheets, stateFile);

        Result result = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));

        assertEquals(0, result.exitCode());
        String appendBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith(":append?valueInputOption=RAW"))
                .findFirst().orElseThrow().body();
        assertEquals("uuid-1", JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(5).getAsString());
    }

    @Test
    void enableBankingSyncCreatesOneTabPerMonthAndRoutesRows() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-1", "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "1200.50", "currency": "EUR"},
                   "remittance_information": ["Nomina"],
                   "booking_date": "2026-07-31"},
                  {"entry_reference": "ref-2", "credit_debit_indicator": "DRDT",
                   "transaction_amount": {"amount": "30.10", "currency": "EUR"},
                   "remittance_information": ["Compra"],
                   "booking_date": "2026-08-01"}
                ]}
                """);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "Hoja 1");
        sheets.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        stubMonthSheet(sheets, "2026-07");
        stubMonthSheet(sheets, "2026-08");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = mainFor(cfg, http, sheets, stateFile);

        Result first = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, first.exitCode());
        assertTrue(first.stdout().contains("appended 2 new row(s)"));

        // Two distinct addSheet bodies, one per month.
        List<String> addSheetBodies = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("/sheet-1:batchUpdate"))
                .map(r -> r.body()).toList();
        assertEquals(2, addSheetBodies.size());
        Set<String> createdTitles = new java.util.HashSet<>();
        for (String body : addSheetBodies) {
            createdTitles.add(JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonArray("requests").get(0).getAsJsonObject()
                    .getAsJsonObject("addSheet").getAsJsonObject("properties").get("title").getAsString());
        }
        assertEquals(Set.of("2026-07", "2026-08"), createdTitles);

        // Two quoted appends; each contains exactly the row whose Fecha matches its tab.
        List<FakeHttp.Request> appends = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith(":append?valueInputOption=RAW"))
                .toList();
        assertEquals(2, appends.size());
        Map<String, String> appendedIdsByMonth = new java.util.HashMap<>();
        for (FakeHttp.Request append : appends) {
            String month = append.url().contains("'2026-07'!") ? "2026-07" : "2026-08";
            JsonArray rows = JsonParser.parseString(append.body()).getAsJsonObject().getAsJsonArray("values");
            assertEquals(1, rows.size());
            assertEquals(month, rows.get(0).getAsJsonArray().get(0).getAsString().substring(0, 7));
            appendedIdsByMonth.put(month, rows.get(0).getAsJsonArray().get(4).getAsString());
        }

        // Mark both months' ids as present and re-stub metadata with the created tabs, then sync again.
        stubSpreadsheetTitles(sheets, "Hoja 1", "2026-07", "2026-08");
        sheets.stub("/sheet-1/values/'2026-07'!E2:E",
                "{\"values\": [[\"" + appendedIdsByMonth.get("2026-07") + "\"]]}");
        sheets.stub("/sheet-1/values/'2026-08'!E2:E",
                "{\"values\": [[\"" + appendedIdsByMonth.get("2026-08") + "\"]]}");

        Result second = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("appended 0 new row(s), skipped 2 duplicate(s)"));
        assertEquals(2, sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("/sheet-1:batchUpdate")).count());
    }

    @Test
    void enableBankingSyncSkipsAppendForAllDuplicateMonth() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "2026-08");
        stubMonthSheet(sheets, "2026-08");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = mainFor(cfg, http, sheets, stateFile);

        // Run 1: appends the row; capture the appended id and the request count.
        Result first = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, first.exitCode());
        assertTrue(first.stdout().contains("appended 1 new row(s)"));
        String appendBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method())
                        && r.url().endsWith("/values/'2026-08'!A1:F:append?valueInputOption=RAW"))
                .findFirst().orElseThrow().body();
        String transactionId = JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(4).getAsString();
        int requestsAfterRun1 = sheets.requests.size();

        // Run 2: the month tab already holds the id; no append may happen.
        sheets.stub("/sheet-1/values/'2026-08'!E2:E", "{\"values\": [[\"" + transactionId + "\"]]}");

        Result second = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("appended 0 new row(s), skipped 1 duplicate(s)"));
        assertTrue(sheets.requests.stream().skip(requestsAfterRun1)
                .noneMatch(r -> r.url().contains(":append")));
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
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
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
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "2026-08");
        sheets.stub("/sheet-1/values/'2026-08'!E2:E", 403, "{\"error\":{\"message\":\"permission denied\"}}");
        Path stateFile = sessionState();

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
        Path stateFile = sessionState();

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

    /** Writes the runtime state file with the Enable Banking session id and returns its path. */
    private Path sessionState() throws IOException {
        Path stateFile = tempDir.resolve("state.properties");
        Files.writeString(stateFile, "enablebanking.session_id=sess-eb-1\n");
        return stateFile;
    }

    private static Result runEnableBanking(FakeHttp http, Path stateFile, AppConfig config, String... args) {
        return runEnableBanking(http, SheetsTestSupport.fakeHttp(), stateFile, config, args);
    }

    private static Result runEnableBanking(FakeHttp http, FakeHttp sheets, Path stateFile,
            AppConfig config, String... args) {
        return CliTestSupport.capture(() -> mainFor(config, http, sheets, stateFile).dispatch(args));
    }

    /** Main wired with the Enable Banking client and the given Sheets fake backend. */
    private static Main mainFor(AppConfig cfg, FakeHttp http, FakeHttp sheets, Path stateFile) {
        return new Main(c -> enableBankingClient(c, http), () -> cfg,
                () -> new GoogleSheetsClient(SheetsTestSupport.config(), sheets), stateFile, () -> { });
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

    /** Stubs the session response with the given JSON array of account ids. */
    private static void stubSession(FakeHttp http, String accountsJson) {
        http.stub("/sessions/sess-eb-1",
                "{\"status\": \"AUTHORIZED\", \"accounts\": " + accountsJson + "}");
    }

    /** Stubs the account-details response for one account id. */
    private static void stubAccountDetails(FakeHttp http, String uuid, String name) {
        http.stub("/accounts/" + uuid + "/details", "{\"name\": \"" + name + "\"}");
    }

    /** Stubs the spreadsheet metadata response with the given tab titles. */
    private static void stubSpreadsheetTitles(FakeHttp sheets, String... titles) {
        SheetsTestSupport.stubToken(sheets);
        StringBuilder json = new StringBuilder("{\"sheets\":[");
        for (int i = 0; i < titles.length; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"properties\":{\"title\":\"").append(titles[i]).append("\"}}");
        }
        json.append("]}");
        sheets.stub("/sheet-1?fields=sheets.properties.title", json.toString());
    }

    /** Stubs one existing month tab: header row, empty id column, and a successful append. */
    private static void stubMonthSheet(FakeHttp sheets, String month) {
        sheets.stub("/sheet-1/values/'" + month + "'!A1:F1", HEADER_JSON);
        sheets.stub("/sheet-1/values/'" + month + "'!E2:E", "{}");
        sheets.stub("/sheet-1/values/'" + month + "'!A1:F:append?valueInputOption=RAW",
                "{\"spreadsheetId\":\"sheet-1\"}");
    }
}
