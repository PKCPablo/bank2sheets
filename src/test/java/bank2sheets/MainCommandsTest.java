package bank2sheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.config.AccountConfig;
import bank2sheets.config.AppConfig;
import bank2sheets.config.SheetsConfig;
import bank2sheets.openbanking.saltedge.FakeHttp;
import bank2sheets.openbanking.saltedge.SaltEdgeClient;
import bank2sheets.sheets.FakeSheetsHttp;
import bank2sheets.sheets.GoogleSheetsClient;
import bank2sheets.sheets.SheetsTestSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntSupplier;
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
        AppConfig noCredentials = new AppConfig("", "", "cust-1", "", List.of());

        Result result = runCli(http, stateFile, noCredentials, "connect");

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

        Result result = capture(() -> new Main(c -> new SaltEdgeClient(c, http), () -> config("conn-1"),
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

    private static Result runCli(FakeHttp http, Path stateFile, AppConfig config, String... args) {
        return runCli(http, new FakeSheetsHttp(), stateFile, config, args);
    }

    private static Result runCli(FakeHttp http, FakeSheetsHttp sheets, Path stateFile, AppConfig config,
            String... args) {
        return capture(() -> new Main(c -> new SaltEdgeClient(c, http), () -> config,
                () -> new GoogleSheetsClient(SheetsTestSupport.config(), sheets), stateFile, () -> { })
                .dispatch(args));
    }

    /**
     * Runs the action while capturing System.out/System.err, then restores them.
     *
     * <p>ponytail: mirrors MainTest.capture; extract a shared test helper on next use.
     */
    private static Result capture(IntSupplier action) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = action.getAsInt();
            return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
