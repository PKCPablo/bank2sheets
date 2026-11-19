package bank2sheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.CliTestSupport.Result;
import bank2sheets.config.AccountConfig;
import bank2sheets.config.AppConfig;
import bank2sheets.config.SheetsConfig;
import bank2sheets.http.FakeHttp;
import bank2sheets.http.HttpExchange.Response;
import bank2sheets.openbanking.enablebanking.AuthorizationCodeReceiver;
import bank2sheets.openbanking.enablebanking.EnableBankingClient;
import bank2sheets.sheets.GoogleSheetsClient;
import bank2sheets.sheets.SheetsTestSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.time.YearMonth;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end command tests: connect/fetch/sync driven through Main with a fake
 * HTTP backend and a temp runtime state file. No real credentials or network
 * calls.
 */
class MainCommandsTest {

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
                   "remittance_information": ["TRANSFERENCIAS // TRANSFERENCIA REALIZADA // GYM"],
                   "booking_date": "2026-08-01"}
                ]}
                """);
        Path stateFile = sessionState();

        Result result = runEnableBanking(http, stateFile, enableBankingConfig(""), "fetch");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Account uuid-1 (Cuenta Nominas): 1 transaction(s)"));
        assertTrue(result.stdout().contains("\"id\":\"eb:ref-1:"));
        assertTrue(result.stdout().contains(
                "\"description\":\"GYM\",\"amount\":1200.50,\"currency\":\"EUR\","
                        + "\"madeOn\":\"2026-08-01\"}"));
    }

    @Test
    void enableBankingSyncAppendsThenIsIdempotent() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-1", "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "1200.50", "currency": "EUR"},
                   "remittance_information": ["TRANSFERENCIAS // TRANSFERENCIA REALIZADA // GYM"],
                   "booking_date": "2026-08-01"}
                ]}
                """);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "Agosto 2026");
        stubMonthSheet(sheets, "Agosto 2026");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = mainFor(cfg, http, sheets, stateFile);

        Result first = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, first.exitCode());
        assertTrue(first.stdout().contains("appended 1 new row(s)"));
        String appendBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"))
                .findFirst().orElseThrow().body();
        assertEquals("'GYM", JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(2).getAsString());
        assertEquals("'Cuenta Nominas", JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(3).getAsString());
        assertEquals("2026-08-01", JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(0).getAsString());

        // Mark the appended TransactionId as already present in the ledger, then sync again.
        String ledgerBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("'_sync'!A2:B:append?valueInputOption=RAW"))
                .findFirst().orElseThrow().body();
        String transactionId = JsonParser.parseString(ledgerBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(1).getAsString();
        sheets.stub("/sheet-1/values/'_sync'!A:B",
                "{\"values\": [[\"Agosto 2026\",\"" + transactionId + "\"]]}");

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
        stubSpreadsheetTitles(sheets, "Agosto 2026");
        stubMonthSheet(sheets, "Agosto 2026");
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
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"))
                .map(r -> r.body()).toList();
        JsonArray values = JsonParser.parseString(appendBodies.get(0)).getAsJsonObject()
                .getAsJsonArray("values");
        assertEquals(2, values.size());
        assertEquals("'Cuenta Nominas", values.get(0).getAsJsonArray().get(3).getAsString());
        assertEquals("'Cuenta Ahorro", values.get(1).getAsJsonArray().get(3).getAsString());

        // Mark both appended TransactionIds as already present in the ledger, then sync again.
        String ledgerBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("'_sync'!A2:B:append?valueInputOption=RAW"))
                .findFirst().orElseThrow().body();
        JsonArray ledgerValues = JsonParser.parseString(ledgerBody).getAsJsonObject().getAsJsonArray("values");
        String firstId = ledgerValues.get(0).getAsJsonArray().get(1).getAsString();
        String secondId = ledgerValues.get(1).getAsJsonArray().get(1).getAsString();
        sheets.stub("/sheet-1/values/'_sync'!A:B",
                "{\"values\": [[\"Agosto 2026\",\"" + firstId + "\"], [\"Agosto 2026\",\"" + secondId + "\"]]}");

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
        stubSpreadsheetTitles(sheets, "Agosto 2026");
        stubMonthSheet(sheets, "Agosto 2026");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = mainFor(cfg, http, sheets, stateFile);

        Result result = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));

        assertEquals(0, result.exitCode());
        String appendBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"))
                .findFirst().orElseThrow().body();
        assertEquals("'uuid-1", JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(3).getAsString());
    }

    @Test
    void enableBankingSyncUsesConfiguredLabelWithoutAccountDetails() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "Agosto 2026");
        stubMonthSheet(sheets, "Agosto 2026");
        Path stateFile = sessionState();
        AppConfig cfg = new AppConfig("app-1", TestKeys.base64Pem(),
                "http://127.0.0.1:8765/bank2sheets/callback", "sess-eb-1",
                List.of(new AccountConfig("enablebanking", "uuid-1", "Cuenta Nóminas")));
        Main main = mainFor(cfg, http, sheets, stateFile);

        Result result = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));

        assertEquals(0, result.exitCode());
        String appendBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"))
                .findFirst().orElseThrow().body();
        assertEquals("'Cuenta Nóminas", JsonParser.parseString(appendBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(3).getAsString());
        assertTrue(http.requests.stream()
                .noneMatch(r -> r.url().endsWith("/details") || r.url().endsWith("/sessions")));
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
        stubMonthSheet(sheets, "Julio 2026");
        stubMonthSheet(sheets, "Agosto 2026");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = mainFor(cfg, http, sheets, stateFile);

        Result first = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, first.exitCode());
        assertTrue(first.stdout().contains("appended 2 new row(s)"));

        // One addSheet (the hidden _sync ledger) plus two duplicateSheet bodies for the months.
        List<String> batchUpdates = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("/sheet-1:batchUpdate"))
                .map(r -> r.body()).toList();
        assertEquals(3, batchUpdates.size());
        Set<String> duplicateTitles = new java.util.HashSet<>();
        Set<Integer> duplicateSourceIds = new java.util.HashSet<>();
        int addSheetCount = 0;
        for (String body : batchUpdates) {
            JsonObject request = JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonArray("requests").get(0).getAsJsonObject();
            if (request.has("addSheet")) {
                addSheetCount++;
                assertEquals("_sync", request.getAsJsonObject("addSheet")
                        .getAsJsonObject("properties").get("title").getAsString());
            } else {
                JsonObject duplicate = request.getAsJsonObject("duplicateSheet");
                duplicateTitles.add(duplicate.get("newSheetName").getAsString());
                duplicateSourceIds.add(duplicate.get("sourceSheetId").getAsInt());
            }
        }
        assertEquals(1, addSheetCount);
        assertEquals(Set.of("Julio 2026", "Agosto 2026"), duplicateTitles);
        assertEquals(Set.of(0), duplicateSourceIds);

        // Two quoted appends; each contains exactly the row whose Fecha matches its tab.
        List<FakeHttp.Request> appends = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("!A1:D:append?valueInputOption=USER_ENTERED"))
                .toList();
        assertEquals(2, appends.size());
        for (FakeHttp.Request append : appends) {
            String expectedFecha = append.url().contains("'Julio%202026'!") ? "2026-07-31" : "2026-08-01";
            JsonArray rows = JsonParser.parseString(append.body()).getAsJsonObject().getAsJsonArray("values");
            assertEquals(1, rows.size());
            assertEquals(expectedFecha, rows.get(0).getAsJsonArray().get(0).getAsString());
        }

        // Mark both months' ids as present in the ledger and re-stub metadata with the created tabs.
        stubSpreadsheetTitles(sheets, "Hoja 1", "Julio 2026", "Agosto 2026", "_sync");
        List<FakeHttp.Request> ledgerAppends = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("'_sync'!A2:B:append?valueInputOption=RAW"))
                .toList();
        assertEquals(2, ledgerAppends.size());
        String julioId = JsonParser.parseString(ledgerAppends.get(0).body()).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(1).getAsString();
        String agostoId = JsonParser.parseString(ledgerAppends.get(1).body()).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(1).getAsString();
        sheets.stub("/sheet-1/values/'_sync'!A:B",
                "{\"values\": [[\"Julio 2026\",\"" + julioId + "\"], [\"Agosto 2026\",\"" + agostoId + "\"]]}");

        Result second = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("appended 0 new row(s), skipped 2 duplicate(s)"));
        assertEquals(3, sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("/sheet-1:batchUpdate")).count());
    }

    @Test
    void enableBankingSyncWritesMonthRowsInDateOrder() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-1", "credit_debit_indicator": "DRDT",
                   "transaction_amount": {"amount": "10.00", "currency": "EUR"},
                   "remittance_information": ["Tarde"],
                   "booking_date": "2026-08-20"},
                  {"entry_reference": "ref-2", "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "20.00", "currency": "EUR"},
                   "remittance_information": ["Temprano"],
                   "booking_date": "2026-08-03"}
                ]}
                """);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "Agosto 2026");
        stubMonthSheet(sheets, "Agosto 2026");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = mainFor(cfg, http, sheets, stateFile);

        Result result = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));

        assertEquals(0, result.exitCode());
        List<FakeHttp.Request> appends = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"))
                .toList();
        assertEquals(1, appends.size());
        JsonArray rows = JsonParser.parseString(appends.get(0).body()).getAsJsonObject()
                .getAsJsonArray("values");
        assertEquals(2, rows.size());
        assertEquals("2026-08-03", rows.get(0).getAsJsonArray().get(0).getAsString());
        assertEquals("2026-08-20", rows.get(1).getAsJsonArray().get(0).getAsString());
    }

    @Test
    void enableBankingSyncSkipsAppendForAllDuplicateMonth() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", NOMINA_TRANSACTION_JSON);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "Agosto 2026");
        stubMonthSheet(sheets, "Agosto 2026");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = mainFor(cfg, http, sheets, stateFile);

        // Run 1: appends the row; capture the appended id and the request count.
        Result first = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, first.exitCode());
        assertTrue(first.stdout().contains("appended 1 new row(s)"));
        String appendBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method())
                        && r.url().endsWith("/values/'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"))
                .findFirst().orElseThrow().body();
        String ledgerBody = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method())
                        && r.url().endsWith("'_sync'!A2:B:append?valueInputOption=RAW"))
                .findFirst().orElseThrow().body();
        String transactionId = JsonParser.parseString(ledgerBody).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray().get(1).getAsString();
        int requestsAfterRun1 = sheets.requests.size();

        // Run 2: the ledger already holds the id; no visible append may happen.
        sheets.stub("/sheet-1/values/'_sync'!A:B",
                "{\"values\": [[\"Agosto 2026\",\"" + transactionId + "\"]]}");
        stubSpreadsheetTitles(sheets, "Agosto 2026", "_sync");

        Result second = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));
        assertEquals(0, second.exitCode());
        assertTrue(second.stdout().contains("appended 0 new row(s), skipped 1 duplicate(s)"));
        assertTrue(sheets.requests.stream().skip(requestsAfterRun1)
                .noneMatch(r -> r.url().endsWith("'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED")));
    }

    @Test
    void enableBankingSyncProtectsClosedMonthsAfterAppend() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-1", "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "1200.50", "currency": "EUR"},
                   "remittance_information": ["Nomina"],
                   "booking_date": "2026-10-15"}
                ]}
                """);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "Octubre 2026", "_sync");
        stubMonthSheet(sheets, "Octubre 2026");
        SheetsTestSupport.stubProtectionMetadata(sheets,
                "{\"sheets\":[{\"properties\":{\"sheetId\":7,\"title\":\"Octubre 2026\"}}]}");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = new Main(c -> enableBankingClient(c, http), () -> cfg,
                () -> new GoogleSheetsClient(SheetsTestSupport.config(), sheets), stateFile, () -> { },
                () -> YearMonth.of(2026, 11));

        Result result = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("protected 1 closed month tab(s)"));
        List<FakeHttp.Request> protectionUpdates = sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("/sheet-1:batchUpdate")
                        && r.body().contains("addProtectedRange"))
                .toList();
        assertEquals(1, protectionUpdates.size());
        JsonObject protectedRange = JsonParser.parseString(protectionUpdates.get(0).body()).getAsJsonObject()
                .getAsJsonArray("requests").get(0).getAsJsonObject()
                .getAsJsonObject("addProtectedRange").getAsJsonObject("protectedRange");
        assertEquals(7, protectedRange.getAsJsonObject("range").get("sheetId").getAsInt());
        assertTrue(protectedRange.get("warningOnly").getAsBoolean());
        int appendIndex = sheets.requests.stream()
                .collect(java.util.stream.Collectors.toList())
                .indexOf(sheets.requests.stream()
                        .filter(r -> r.url().endsWith("'Octubre%202026'!A1:D:append?valueInputOption=USER_ENTERED"))
                        .findFirst().orElseThrow());
        assertTrue(sheets.requests.indexOf(protectionUpdates.get(0)) > appendIndex);
    }

    @Test
    void syncLeavesCurrentMonthUnprotected() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.stub("/accounts/uuid-1/transactions?transaction_status=BOOK", """
                {"transactions": [
                  {"entry_reference": "ref-1", "credit_debit_indicator": "CRDT",
                   "transaction_amount": {"amount": "1200.50", "currency": "EUR"},
                   "remittance_information": ["Nomina"],
                   "booking_date": "2026-11-01"}
                ]}
                """);
        FakeHttp sheets = SheetsTestSupport.fakeHttp();
        stubSpreadsheetTitles(sheets, "Noviembre 2026", "_sync");
        stubMonthSheet(sheets, "Noviembre 2026");
        SheetsTestSupport.stubProtectionMetadata(sheets,
                "{\"sheets\":[{\"properties\":{\"sheetId\":9,\"title\":\"Noviembre 2026\"}}]}");
        Path stateFile = sessionState();
        AppConfig cfg = enableBankingConfig("sess-eb-1");
        Main main = new Main(c -> enableBankingClient(c, http), () -> cfg,
                () -> new GoogleSheetsClient(SheetsTestSupport.config(), sheets), stateFile, () -> { },
                () -> YearMonth.of(2026, 11));

        Result result = CliTestSupport.capture(() -> main.dispatch(new String[] {"sync"}));

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("protected 0 closed month tab(s)"));
        assertTrue(sheets.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("/sheet-1:batchUpdate"))
                .noneMatch(r -> r.body().contains("addProtectedRange")));
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
                () -> GoogleSheetsClient.create(new SheetsConfig("", "")),
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
        stubSpreadsheetTitles(sheets, "Agosto 2026");
        stubLedger(sheets, "{}");
        sheets.stub("/sheet-1/values/'_sync'!A:B", 403, "{\"error\":{\"message\":\"permission denied\"}}");
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

    @Test
    void enableBankingSyncWithRateLimitExitsNonZeroAndExplains() throws IOException {
        FakeHttp http = new FakeHttp(EB_BASE);
        stubSession(http, "[\"uuid-1\"]");
        stubAccountDetails(http, "uuid-1", "Cuenta Nominas");
        http.responses.put("/accounts/uuid-1/transactions?transaction_status=BOOK", new Response(429,
                "{\"code\":429,\"message\":\"[HUB046] Allowed number of accesses exceeded for consent.\","
                        + "\"detail\":{\"message\":\"[HUB046] Allowed number of accesses exceeded for consent.\","
                        + "\"error_name\":\"RateLimitException\",\"error_data\":{}},"
                        + "\"error\":\"ASPSP_RATE_LIMIT_EXCEEDED\"}"));
        Path stateFile = sessionState();

        Result result = runEnableBanking(http, stateFile, enableBankingConfig(""), "sync");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("sync:"));
        assertTrue(result.stderr().contains("ASPSP_RATE_LIMIT_EXCEEDED"));
        assertTrue(result.stderr().contains("background AIS access allowance"));
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
        SheetsTestSupport.stubSheetTitles(sheets, titles);
        SheetsTestSupport.stubProtectionMetadata(sheets, "{}");
    }

    /** Stubs one existing month tab: header row, empty ledger, and a successful append. */
    private static void stubMonthSheet(FakeHttp sheets, String title) {
        String encoded = title.replace(" ", "%20");
        sheets.stub("/sheet-1/values/'" + encoded + "'!A1:D1", SheetsTestSupport.HEADER_JSON);
        stubLedger(sheets, "{}");
        sheets.stub("/sheet-1/values/'" + encoded + "'!A1:D:append?valueInputOption=USER_ENTERED",
                "{\"spreadsheetId\":\"sheet-1\"}");
    }

    /** Stubs the hidden _sync ledger: tab creation, read range, header append, and data append. */
    private static void stubLedger(FakeHttp sheets, String dataJson) {
        sheets.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        sheets.stub("/sheet-1/values/'_sync'!A:B", dataJson);
        sheets.stub("/sheet-1/values/'_sync'!A1:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        sheets.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
    }
}
