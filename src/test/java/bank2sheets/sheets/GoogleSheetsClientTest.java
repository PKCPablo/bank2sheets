package bank2sheets.sheets;

import bank2sheets.Bank2SheetsException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.config.SheetsConfig;
import bank2sheets.http.FakeHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * GoogleSheetsClient tests against the shared in-memory fake HTTP backend; no
 * real credentials or Google API calls are involved. The service account JSON is
 * a throwaway test key (see {@link SheetsTestSupport}) used only to exercise JWT
 * signing against the fake token endpoint.
 */
class GoogleSheetsClientTest {

    /** Fake backend with the token endpoint stubbed and a client built on it. */
    private static GoogleSheetsClient client(FakeHttp http) {
        SheetsTestSupport.stubToken(http);
        return new GoogleSheetsClient(SheetsTestSupport.config(), http);
    }

    @Test
    void existingTransactionIdsForExistingMonthReadsQuotedRange() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "Agosto 2026", "_sync");
        SheetsTestSupport.stubLedgerRead(http, """
                {"values":[["Tab","TransactionId"],["Agosto 2026","t1"],["Julio 2026","t9"],["Agosto 2026","t2"]]}
                """);
        GoogleSheetsClient client = client(http);

        assertEquals(Set.of("t1", "t2"), client.existingTransactionIds(YearMonth.of(2026, 8)));

        assertEquals(1, http.requests.stream()
                .filter(r -> r.url().endsWith(SheetsTestSupport.SHEET_METADATA_PATH)).count());
        assertTrue(http.requests.stream().noneMatch(r -> r.url().contains(":batchUpdate")));
    }

    @Test
    void existingTransactionIdsForMissingMonthCreatesSheetThenReads() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "Hoja 1");
        http.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A1:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        GoogleSheetsClient client = client(http);

        assertTrue(client.existingTransactionIds(YearMonth.of(2026, 11)).isEmpty());

        // requests: 0=token, 1=metadata, 2=month duplicateSheet, 3=_sync addSheet, 4=_sync header append, 5=ledger read
        assertEquals(6, http.requests.size());
        FakeHttp.Request duplicate = http.requests.get(2);
        assertEquals("POST", duplicate.method());
        assertEquals("https://sheets.googleapis.com/v4/spreadsheets/sheet-1:batchUpdate", duplicate.url());
        JsonObject body = JsonParser.parseString(duplicate.body()).getAsJsonObject();
        JsonObject duplicateRequest = body.getAsJsonArray("requests").get(0).getAsJsonObject()
                .getAsJsonObject("duplicateSheet");
        assertEquals(0, duplicateRequest.get("sourceSheetId").getAsInt());
        assertEquals("Noviembre 2026", duplicateRequest.get("newSheetName").getAsString());
        assertEquals(1, duplicateRequest.get("insertSheetIndex").getAsInt());
        assertEquals(1, body.getAsJsonArray("requests").size());
        FakeHttp.Request helperAddSheet = http.requests.get(3);
        JsonObject helperBody = JsonParser.parseString(helperAddSheet.body()).getAsJsonObject();
        JsonObject helperProperties = helperBody.getAsJsonArray("requests").get(0).getAsJsonObject()
                .getAsJsonObject("addSheet").getAsJsonObject("properties");
        assertEquals("_sync", helperProperties.get("title").getAsString());
        assertEquals(true, helperProperties.get("hidden").getAsBoolean());
        FakeHttp.Request headerAppend = http.requests.get(4);
        assertEquals("https://sheets.googleapis.com/v4/spreadsheets/sheet-1/values/'_sync'!A1:B:append?valueInputOption=RAW",
                headerAppend.url());
        assertEquals("{\"values\":[[\"Tab\",\"TransactionId\"]]}", headerAppend.body());
        FakeHttp.Request read = http.requests.get(5);
        assertEquals("https://sheets.googleapis.com/v4/spreadsheets/sheet-1/values/'_sync'!A:B", read.url());

        // Second month: metadata stays at one read, _sync is not re-created, only the month tab is.
        assertTrue(client.existingTransactionIds(YearMonth.of(2026, 12)).isEmpty());
        assertEquals(1, http.requests.stream()
                .filter(r -> r.url().endsWith(SheetsTestSupport.SHEET_METADATA_PATH)).count());
        FakeHttp.Request secondDuplicate = http.requests.get(6);
        assertEquals("POST", secondDuplicate.method());
        JsonObject secondBody = JsonParser.parseString(secondDuplicate.body()).getAsJsonObject();
        JsonObject secondRequest = secondBody.getAsJsonArray("requests").get(0).getAsJsonObject()
                .getAsJsonObject("duplicateSheet");
        assertEquals(0, secondRequest.get("sourceSheetId").getAsInt());
        assertEquals("Diciembre 2026", secondRequest.get("newSheetName").getAsString());
        assertEquals(3, secondRequest.get("insertSheetIndex").getAsInt());
        assertEquals(3, http.requests.stream().filter(r -> r.url().contains(":batchUpdate")).count());
    }

    @Test
    void createsMonthTabByDuplicatingFirstSheet() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "Plantilla", "_sync");
        http.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        GoogleSheetsClient client = client(http);

        assertTrue(client.existingTransactionIds(YearMonth.of(2026, 11)).isEmpty());

        FakeHttp.Request duplicate = http.requests.stream()
                .filter(r -> r.url().contains(":batchUpdate")).findFirst().orElseThrow();
        JsonObject duplicateRequest = JsonParser.parseString(duplicate.body()).getAsJsonObject()
                .getAsJsonArray("requests").get(0).getAsJsonObject().getAsJsonObject("duplicateSheet");
        assertEquals(0, duplicateRequest.get("sourceSheetId").getAsInt());
        assertEquals("Noviembre 2026", duplicateRequest.get("newSheetName").getAsString());
        assertEquals(2, duplicateRequest.get("insertSheetIndex").getAsInt());
        // No addSheet body carries the month title.
        assertTrue(http.requests.stream().filter(r -> r.url().contains(":batchUpdate"))
                .noneMatch(r -> r.body().contains("addSheet")
                        && r.body().contains("Noviembre 2026")));
    }

    @Test
    void fallsBackToBareTabWhenFirstSheetIsAMonthTab() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "Octubre 2026");
        http.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A1:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        GoogleSheetsClient client = client(http);

        assertTrue(client.existingTransactionIds(YearMonth.of(2026, 11)).isEmpty());

        FakeHttp.Request addSheet = http.requests.stream()
                .filter(r -> r.url().contains(":batchUpdate")).findFirst().orElseThrow();
        JsonObject properties = JsonParser.parseString(addSheet.body()).getAsJsonObject()
                .getAsJsonArray("requests").get(0).getAsJsonObject()
                .getAsJsonObject("addSheet").getAsJsonObject("properties");
        assertEquals("Noviembre 2026", properties.get("title").getAsString());
        assertFalse(properties.has("hidden"));
    }

    @Test
    void fallsBackToBareTabWhenFirstSheetIsTheSyncLedger() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "_sync");
        http.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        GoogleSheetsClient client = client(http);

        assertTrue(client.existingTransactionIds(YearMonth.of(2026, 11)).isEmpty());

        FakeHttp.Request addSheet = http.requests.stream()
                .filter(r -> r.url().contains(":batchUpdate")).findFirst().orElseThrow();
        JsonObject properties = JsonParser.parseString(addSheet.body()).getAsJsonObject()
                .getAsJsonArray("requests").get(0).getAsJsonObject()
                .getAsJsonObject("addSheet").getAsJsonObject("properties");
        assertEquals("Noviembre 2026", properties.get("title").getAsString());
        assertFalse(properties.has("hidden"));
    }

    @Test
    void existingTransactionIdsIsEmptyWhenSheetHasNoData() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "Agosto 2026", "_sync");
        SheetsTestSupport.stubLedgerRead(http, "{}");
        GoogleSheetsClient client = client(http);

        assertTrue(client.existingTransactionIds(YearMonth.of(2026, 8)).isEmpty());
    }

    @Test
    void tokenRequestUsesJwtBearerGrantAndCachesToken() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "Agosto 2026", "_sync");
        SheetsTestSupport.stubLedgerRead(http, "{}");
        GoogleSheetsClient client = client(http);

        client.existingTransactionIds(YearMonth.of(2026, 8));
        client.existingTransactionIds(YearMonth.of(2026, 8));

        long tokenRequests = http.requests.stream()
                .filter(r -> r.url().equals("https://oauth2.googleapis.com/token"))
                .count();
        assertEquals(1, tokenRequests);
        FakeHttp.Request token = http.requests.get(0);
        assertEquals("POST", token.method());
        assertTrue(token.body().startsWith(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="));
        String assertion = token.body().substring(token.body().indexOf("assertion=") + "assertion=".length());
        assertEquals(3, assertion.split("\\.").length);
        FakeHttp.Request firstApiCall = http.requests.get(1);
        assertEquals("Bearer tok-123", firstApiCall.headers().get("Authorization"));
    }

    @Test
    void appendRowsWritesHeaderThenDataWithAmountsAsNumbers() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubExistingMonth(http, "Agosto 2026", "{}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:D:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow(LocalDate.of(2026, 8, 1), new BigDecimal("1200.50"), "Nomina", "Cuenta Principal", "t1"),
                new SheetRow(LocalDate.of(2026, 8, 2), new BigDecimal("-3.20"), "Cafe", "Cuenta Principal", "t2")));

        // requests: 0=token, 1=metadata, 2=ledger read, 3=header read, 4=header append, 5=data append, 6=ledger append
        assertEquals(7, http.requests.size());
        FakeHttp.Request headerAppend = http.requests.get(4);
        assertEquals("POST", headerAppend.method());
        assertTrue(headerAppend.url().endsWith(":append?valueInputOption=RAW"));
        assertEquals(SheetsTestSupport.HEADER_JSON,
                headerAppend.body());

        FakeHttp.Request dataAppend = http.requests.get(5);
        assertTrue(dataAppend.url().endsWith("'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"));
        String body = dataAppend.body();
        assertFalse(body.contains("\"1200.50\""));
        JsonArray values = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("values");
        assertEquals(2, values.size());
        JsonArray first = values.get(0).getAsJsonArray();
        assertEquals("2026-08-01", first.get(0).getAsString());
        assertEquals(new BigDecimal("1200.50"), first.get(1).getAsBigDecimal());
        assertEquals("'Nomina", first.get(2).getAsString());
        assertEquals("'Cuenta Principal", first.get(3).getAsString());
        assertEquals(new BigDecimal("-3.20"), values.get(1).getAsJsonArray().get(1).getAsBigDecimal());
    }

    @Test
    void appendRowsEscapesTextColumnsAsLiteralText() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubExistingMonth(http, "Agosto 2026", SheetsTestSupport.HEADER_JSON);
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow(LocalDate.of(2026, 8, 1), new BigDecimal("100"), "=SUM(A1:A2)", "2-3-4", "t1")));

        FakeHttp.Request dataAppend = http.requests.stream()
                .filter(r -> "POST".equals(r.method())
                        && r.url().endsWith("'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"))
                .findFirst().orElseThrow();
        JsonArray first = JsonParser.parseString(dataAppend.body()).getAsJsonObject()
                .getAsJsonArray("values").get(0).getAsJsonArray();
        assertEquals("2026-08-01", first.get(0).getAsString());
        assertEquals(new BigDecimal("100"), first.get(1).getAsBigDecimal());
        assertEquals("'=SUM(A1:A2)", first.get(2).getAsString());
        assertEquals("'2-3-4", first.get(3).getAsString());
    }

    @Test
    void appendRowsRecordsTransactionIdsInHiddenLedger() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubExistingMonth(http, "Agosto 2026", SheetsTestSupport.HEADER_JSON);
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow(LocalDate.of(2026, 8, 1), new BigDecimal("100"), "Nomina", "Cuenta Principal", "t1"),
                new SheetRow(LocalDate.of(2026, 8, 2), new BigDecimal("-3.20"), "Cafe", "Cuenta Principal", "t2")));

        // requests: 0=token, 1=metadata, 2=ledger read, 3=header read, 4=visible append, 5=ledger append
        assertEquals(6, http.requests.size());
        FakeHttp.Request visibleAppend = http.requests.get(4);
        assertTrue(visibleAppend.url().endsWith("/values/'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"));
        FakeHttp.Request ledgerAppend = http.requests.get(5);
        assertEquals("https://sheets.googleapis.com/v4/spreadsheets/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW",
                ledgerAppend.url());
        assertEquals("{\"values\":[[\"Agosto 2026\",\"t1\"],[\"Agosto 2026\",\"t2\"]]}",
                ledgerAppend.body());
    }

    @Test
    void ledgerAppendFailureThrowsAfterVisibleAppend() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubExistingMonth(http, "Agosto 2026", SheetsTestSupport.HEADER_JSON);
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", 500, "{\"error\":{\"message\":\"boom\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.appendRows(YearMonth.of(2026, 8), List.of(
                        new SheetRow(LocalDate.of(2026, 8, 1), new BigDecimal("100"), "Nomina", "Ahorro", "t1"))));
        assertTrue(error.getMessage().contains("append failed"));
        assertTrue(error.getMessage().contains("500"));
        // The visible row was appended before the ledger append failed.
        assertTrue(http.requests.stream().anyMatch(r ->
                r.url().endsWith("/values/'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED")));
    }

    @Test
    void appendRowsSortsRowsByDateAscending() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubExistingMonth(http, "Agosto 2026", SheetsTestSupport.HEADER_JSON);
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow(LocalDate.of(2026, 8, 15), new BigDecimal("10"), "Tarde", "Cuenta Principal", "t-late"),
                new SheetRow(LocalDate.of(2026, 8, 2), new BigDecimal("20"), "Temprano", "Cuenta Principal", "t-early"),
                new SheetRow(LocalDate.of(2026, 8, 15), new BigDecimal("30"), "Tarde-2", "Cuenta Principal", "t-late-2")));

        FakeHttp.Request dataAppend = http.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"))
                .findFirst().orElseThrow();
        JsonArray values = JsonParser.parseString(dataAppend.body()).getAsJsonObject().getAsJsonArray("values");
        assertEquals("2026-08-02", values.get(0).getAsJsonArray().get(0).getAsString());
        assertEquals("2026-08-15", values.get(1).getAsJsonArray().get(0).getAsString());
        assertEquals("2026-08-15", values.get(2).getAsJsonArray().get(0).getAsString());
        // Stable sort: same-date rows keep their input order.
        assertEquals("'Tarde", values.get(1).getAsJsonArray().get(2).getAsString());
        assertEquals("'Tarde-2", values.get(2).getAsJsonArray().get(2).getAsString());
    }

    @Test
    void appendRowsSkipsHeaderWhenAlreadyPresent() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubExistingMonth(http, "Agosto 2026", SheetsTestSupport.HEADER_JSON);
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow(LocalDate.of(2026, 8, 1), new BigDecimal("100"), "Nomina", "Cuenta Principal", "t1")));

        // token + metadata + ledger read + header read + data append + ledger append (no header append, no PUT)
        assertEquals(6, http.requests.size());
        // token, visible data append, ledger append
        assertEquals(3, http.requests.stream().filter(r -> "POST".equals(r.method())).count());
        assertTrue(http.requests.get(4).url().endsWith("'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED"));
        assertTrue(http.requests.stream().noneMatch(r -> "PUT".equals(r.method())));
    }

    @Test
    void appendRowsRejectsLegacySixColumnHeader() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubExistingMonth(http, "Agosto 2026",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\",\"Cuenta\"]]}");
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.appendRows(YearMonth.of(2026, 8), List.of(
                        new SheetRow(LocalDate.of(2026, 8, 1), new BigDecimal("100"), "Nomina", "Ahorro", "t1"))));
        assertTrue(error.getMessage().contains("Fecha"));
        assertTrue(error.getMessage().contains("Movimientos"));
        assertTrue(error.getMessage().contains("Descripción"));
        assertTrue(error.getMessage().contains("Cuenta"));
        assertTrue(error.getMessage().contains("_sync"));
        // Nothing was appended to the legacy tab.
        assertTrue(http.requests.stream().noneMatch(r ->
                r.url().endsWith("/values/'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED")));
    }

    @Test
    void appendRowsWithNoRowsDoesNothing() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of());

        assertTrue(http.requests.isEmpty());
    }

    @Test
    void createFailsFastWithoutServiceAccountConfig() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> GoogleSheetsClient.create(new SheetsConfig("", "sheet-1")));
        assertTrue(error.getMessage().contains("GOOGLE_SERVICE_ACCOUNT_B64"));
    }

    @Test
    void createFailsFastWithoutSpreadsheetConfig() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> GoogleSheetsClient.create(new SheetsConfig(SheetsTestSupport.serviceAccountB64(), "")));
        assertTrue(error.getMessage().contains("GOOGLE_SPREADSHEET_ID"));
    }

    @Test
    void invalidServiceAccountBase64Fails() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        GoogleSheetsClient client = new GoogleSheetsClient(new SheetsConfig("not-base64!!", "sheet-1"), http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.existingTransactionIds(YearMonth.of(2026, 8)));
        assertTrue(error.getMessage().contains("GOOGLE_SERVICE_ACCOUNT_B64"));
    }

    @Test
    void serviceAccountWithoutCredentialsFails() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        String b64 = Base64.getEncoder()
                .encodeToString("{\"type\":\"service_account\"}".getBytes(StandardCharsets.UTF_8));
        GoogleSheetsClient client = new GoogleSheetsClient(new SheetsConfig(b64, "sheet-1"), http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.existingTransactionIds(YearMonth.of(2026, 8)));
        assertTrue(error.getMessage().contains("client_email"));
    }

    @Test
    void nonSuccessMetadataThrows() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub(SheetsTestSupport.SHEET_METADATA_PATH, 403, "{\"error\":{\"message\":\"permission denied\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.existingTransactionIds(YearMonth.of(2026, 8)));
        assertTrue(error.getMessage().contains("metadata failed"));
        assertTrue(error.getMessage().contains("403"));
    }

    @Test
    void nonSuccessCreateSheetThrows() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "Agosto 2026");
        http.stub("/sheet-1:batchUpdate", 500, "{\"error\":{\"message\":\"boom\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.existingTransactionIds(YearMonth.of(2026, 11)));
        assertTrue(error.getMessage().contains("add sheet failed"));
        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    void nonSuccessDuplicateSheetThrows() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "Hoja 1");
        http.stub("/sheet-1:batchUpdate", 500, "{\"error\":{\"message\":\"boom\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.existingTransactionIds(YearMonth.of(2026, 11)));
        assertTrue(error.getMessage().contains("duplicate sheet failed"));
        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    void nonSuccessReadThrows() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "Agosto 2026", "_sync");
        http.stub("/sheet-1/values/'_sync'!A:B", 403, "{\"error\":{\"message\":\"permission denied\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.existingTransactionIds(YearMonth.of(2026, 8)));
        assertTrue(error.getMessage().contains("403"));
    }

    @Test
    void nonSuccessAppendThrows() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubSheetTitles(http, "Agosto 2026", "_sync");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:D1", SheetsTestSupport.HEADER_JSON);
        http.stub("/sheet-1/values/'Agosto%202026'!A1:D:append?valueInputOption=USER_ENTERED", 500, "{\"error\":{\"message\":\"boom\"}}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.appendRows(YearMonth.of(2026, 8), List.of(
                        new SheetRow(LocalDate.of(2026, 8, 1), new BigDecimal("1"), "Nomina", "Ahorro", "t1"))));
        assertTrue(error.getMessage().contains("append failed"));
        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    void protectCompletedMonthsWarnsOnWholeClosedSheet() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubProtectionMetadata(http,
                "{\"sheets\":[{\"properties\":{\"sheetId\":11,\"title\":\"Octubre 2026\"}}]}");
        http.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        assertEquals(1, client.protectCompletedMonths(YearMonth.of(2026, 11)));

        assertEquals(1, http.requests.stream().filter(r -> r.url().contains(":batchUpdate")).count());
        FakeHttp.Request post = http.requests.stream()
                .filter(r -> r.url().contains(":batchUpdate")).findFirst().orElseThrow();
        assertEquals("POST", post.method());
        JsonObject protectedRange = JsonParser.parseString(post.body()).getAsJsonObject()
                .getAsJsonArray("requests").get(0).getAsJsonObject()
                .getAsJsonObject("addProtectedRange").getAsJsonObject("protectedRange");
        assertEquals(true, protectedRange.get("warningOnly").getAsBoolean());
        assertEquals(11, protectedRange.getAsJsonObject("range").get("sheetId").getAsInt());
        assertFalse(protectedRange.getAsJsonObject("range").has("startRowIndex"));
        assertFalse(protectedRange.getAsJsonObject("range").has("startColumnIndex"));
    }

    @Test
    void protectCompletedMonthsSkipsCurrentAndFutureMonths() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubProtectionMetadata(http,
                "{\"sheets\":["
                        + "{\"properties\":{\"sheetId\":11,\"title\":\"Octubre 2026\"}},"
                        + "{\"properties\":{\"sheetId\":12,\"title\":\"Noviembre 2026\"}},"
                        + "{\"properties\":{\"sheetId\":13,\"title\":\"Diciembre 2026\"}}]}");
        http.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        assertEquals(1, client.protectCompletedMonths(YearMonth.of(2026, 11)));

        String body = http.requests.stream().filter(r -> r.url().contains(":batchUpdate"))
                .findFirst().orElseThrow().body();
        assertTrue(body.contains("\"sheetId\":11"));
        assertFalse(body.contains("\"sheetId\":12"));
        assertFalse(body.contains("\"sheetId\":13"));
    }

    @Test
    void protectCompletedMonthsIsIdempotentWhenAlreadyProtected() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubProtectionMetadata(http,
                "{\"sheets\":[{\"properties\":{\"sheetId\":11,\"title\":\"Octubre 2026\"},"
                        + "\"protectedRanges\":[{\"protectedRangeId\":7,\"warningOnly\":true}]}]}");
        GoogleSheetsClient client = client(http);

        assertEquals(0, client.protectCompletedMonths(YearMonth.of(2026, 11)));

        assertTrue(http.requests.stream().noneMatch(r -> r.url().contains(":batchUpdate")));
    }

    @Test
    void protectCompletedMonthsIgnoresNonMonthTabs() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubProtectionMetadata(http,
                "{\"sheets\":["
                        + "{\"properties\":{\"sheetId\":1,\"title\":\"_sync\"}},"
                        + "{\"properties\":{\"sheetId\":2,\"title\":\"Hoja 1\"}},"
                        + "{\"properties\":{\"sheetId\":11,\"title\":\"Octubre 2026\"}}]}");
        http.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        assertEquals(1, client.protectCompletedMonths(YearMonth.of(2026, 11)));

        JsonArray requests = JsonParser.parseString(http.requests.stream()
                .filter(r -> r.url().contains(":batchUpdate")).findFirst().orElseThrow().body())
                .getAsJsonObject().getAsJsonArray("requests");
        assertEquals(1, requests.size());
        assertEquals(11, requests.get(0).getAsJsonObject().getAsJsonObject("addProtectedRange")
                .getAsJsonObject("protectedRange").getAsJsonObject("range").get("sheetId").getAsInt());
    }

    @Test
    void protectCompletedMonthsBatchesMultipleClosedMonths() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubProtectionMetadata(http,
                "{\"sheets\":["
                        + "{\"properties\":{\"sheetId\":7,\"title\":\"Julio 2026\"}},"
                        + "{\"properties\":{\"sheetId\":11,\"title\":\"Octubre 2026\"}}]}");
        http.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        assertEquals(2, client.protectCompletedMonths(YearMonth.of(2026, 11)));

        assertEquals(1, http.requests.stream().filter(r -> r.url().contains(":batchUpdate")).count());
        JsonArray requests = JsonParser.parseString(http.requests.stream()
                .filter(r -> r.url().contains(":batchUpdate")).findFirst().orElseThrow().body())
                .getAsJsonObject().getAsJsonArray("requests");
        assertEquals(2, requests.size());
        assertEquals(7, requests.get(0).getAsJsonObject().getAsJsonObject("addProtectedRange")
                .getAsJsonObject("protectedRange").getAsJsonObject("range").get("sheetId").getAsInt());
        assertEquals(11, requests.get(1).getAsJsonObject().getAsJsonObject("addProtectedRange")
                .getAsJsonObject("protectedRange").getAsJsonObject("range").get("sheetId").getAsInt());
    }

    @Test
    void protectCompletedMonthsDoesNothingWhenNoCandidate() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubProtectionMetadata(http,
                "{\"sheets\":[{\"properties\":{\"sheetId\":12,\"title\":\"Noviembre 2026\"}}]}");
        GoogleSheetsClient client = client(http);

        assertEquals(0, client.protectCompletedMonths(YearMonth.of(2026, 11)));

        assertTrue(http.requests.stream().noneMatch(r -> r.url().contains(":batchUpdate")));
    }

    @Test
    void protectCompletedMonthsNonSuccessMetadataThrows() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub(SheetsTestSupport.PROTECTION_METADATA_PATH, 403, "{\"error\":{\"message\":\"permission denied\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.protectCompletedMonths(YearMonth.of(2026, 11)));
        assertTrue(error.getMessage().contains("metadata failed"));
        assertTrue(error.getMessage().contains("403"));
    }

    @Test
    void protectCompletedMonthsNonSuccessBatchUpdateThrows() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        SheetsTestSupport.stubProtectionMetadata(http,
                "{\"sheets\":[{\"properties\":{\"sheetId\":11,\"title\":\"Octubre 2026\"}}]}");
        http.stub("/sheet-1:batchUpdate", 500, "{\"error\":{\"message\":\"boom\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.protectCompletedMonths(YearMonth.of(2026, 11)));
        assertTrue(error.getMessage().contains("protect month failed"));
        assertTrue(error.getMessage().contains("500"));
        assertTrue(http.requests.stream().anyMatch(r -> r.url().contains(":batchUpdate")));
    }

}
