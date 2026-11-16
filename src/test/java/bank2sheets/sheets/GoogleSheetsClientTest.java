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
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'_sync'!A:B", """
                {"values":[["Tab","TransactionId"],["Agosto 2026","t1"],["Julio 2026","t9"],["Agosto 2026","t2"]]}
                """);
        GoogleSheetsClient client = client(http);

        assertEquals(Set.of("t1", "t2"), client.existingTransactionIds(YearMonth.of(2026, 8)));

        assertEquals(1, http.requests.stream()
                .filter(r -> r.url().endsWith("/sheet-1?fields=sheets.properties.title")).count());
        assertTrue(http.requests.stream().noneMatch(r -> r.url().contains(":batchUpdate")));
    }

    @Test
    void existingTransactionIdsForMissingMonthCreatesSheetThenReads() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Hoja 1\"}}]}");
        http.stub("/sheet-1:batchUpdate", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A1:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        GoogleSheetsClient client = client(http);

        assertTrue(client.existingTransactionIds(YearMonth.of(2026, 11)).isEmpty());

        // requests: 0=token, 1=metadata, 2=month addSheet, 3=_sync addSheet, 4=_sync header append, 5=ledger read
        assertEquals(6, http.requests.size());
        FakeHttp.Request addSheet = http.requests.get(2);
        assertEquals("POST", addSheet.method());
        assertEquals("https://sheets.googleapis.com/v4/spreadsheets/sheet-1:batchUpdate", addSheet.url());
        JsonObject body = JsonParser.parseString(addSheet.body()).getAsJsonObject();
        assertEquals("Noviembre 2026",
                body.getAsJsonArray("requests").get(0).getAsJsonObject()
                        .getAsJsonObject("addSheet").getAsJsonObject("properties").get("title").getAsString());
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
                .filter(r -> r.url().endsWith("/sheet-1?fields=sheets.properties.title")).count());
        FakeHttp.Request secondAddSheet = http.requests.get(6);
        assertEquals("POST", secondAddSheet.method());
        JsonObject secondBody = JsonParser.parseString(secondAddSheet.body()).getAsJsonObject();
        assertEquals("Diciembre 2026",
                secondBody.getAsJsonArray("requests").get(0).getAsJsonObject()
                        .getAsJsonObject("addSheet").getAsJsonObject("properties").get("title").getAsString());
        assertEquals(3, http.requests.stream().filter(r -> r.url().contains(":batchUpdate")).count());
    }

    @Test
    void existingTransactionIdsIsEmptyWhenSheetHasNoData() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        GoogleSheetsClient client = client(http);

        assertTrue(client.existingTransactionIds(YearMonth.of(2026, 8)).isEmpty());
    }

    @Test
    void tokenRequestUsesJwtBearerGrantAndCachesToken() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
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
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F1", "{}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow("2026-08-01", "Nomina", new BigDecimal("1200.50"), "EUR", "t1", "Cuenta Principal"),
                new SheetRow("2026-08-02", "Cafe", new BigDecimal("-3.20"), "EUR", "t2", "Cuenta Principal")));

        // requests: 0=token, 1=metadata, 2=ledger read, 3=header read, 4=header append, 5=data append, 6=ledger append
        assertEquals(7, http.requests.size());
        FakeHttp.Request headerAppend = http.requests.get(4);
        assertEquals("POST", headerAppend.method());
        assertTrue(headerAppend.url().endsWith(":append?valueInputOption=RAW"));
        assertEquals("{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\",\"Cuenta\"]]}",
                headerAppend.body());

        FakeHttp.Request dataAppend = http.requests.get(5);
        String body = dataAppend.body();
        assertFalse(body.contains("\"1200.50\""));
        JsonArray values = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("values");
        assertEquals(2, values.size());
        JsonArray first = values.get(0).getAsJsonArray();
        assertEquals("2026-08-01", first.get(0).getAsString());
        assertEquals("Nomina", first.get(1).getAsString());
        assertEquals(new BigDecimal("1200.50"), first.get(2).getAsBigDecimal());
        assertEquals("EUR", first.get(3).getAsString());
        assertEquals("t1", first.get(4).getAsString());
        assertEquals("Cuenta Principal", first.get(5).getAsString());
        assertEquals(new BigDecimal("-3.20"), values.get(1).getAsJsonArray().get(2).getAsBigDecimal());
    }

    @Test
    void appendRowsRecordsTransactionIdsInHiddenLedger() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F1",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\",\"Cuenta\"]]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow("2026-08-01", "Nomina", new BigDecimal("100"), "EUR", "t1", "Cuenta Principal"),
                new SheetRow("2026-08-02", "Cafe", new BigDecimal("-3.20"), "EUR", "t2", "Cuenta Principal")));

        // requests: 0=token, 1=metadata, 2=ledger read, 3=header read, 4=visible append, 5=ledger append
        assertEquals(6, http.requests.size());
        FakeHttp.Request visibleAppend = http.requests.get(4);
        assertTrue(visibleAppend.url().endsWith("/values/'Agosto%202026'!A1:F:append?valueInputOption=RAW"));
        FakeHttp.Request ledgerAppend = http.requests.get(5);
        assertEquals("https://sheets.googleapis.com/v4/spreadsheets/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW",
                ledgerAppend.url());
        assertEquals("{\"values\":[[\"Agosto 2026\",\"t1\"],[\"Agosto 2026\",\"t2\"]]}",
                ledgerAppend.body());
    }

    @Test
    void ledgerAppendFailureThrowsAfterVisibleAppend() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F1",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\",\"Cuenta\"]]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", 500, "{\"error\":{\"message\":\"boom\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.appendRows(YearMonth.of(2026, 8), List.of(
                        new SheetRow("2026-08-01", "Nomina", new BigDecimal("100"), "EUR", "t1", "Ahorro"))));
        assertTrue(error.getMessage().contains("append failed"));
        assertTrue(error.getMessage().contains("500"));
        // The visible row was appended before the ledger append failed.
        assertTrue(http.requests.stream().anyMatch(r ->
                r.url().endsWith("/values/'Agosto%202026'!A1:F:append?valueInputOption=RAW")));
    }

    @Test
    void appendRowsSortsRowsByDateAscending() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F1",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\",\"Cuenta\"]]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F:append?valueInputOption=RAW",
                "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow("2026-08-15", "Tarde", new BigDecimal("10"), "EUR", "t-late", "Cuenta Principal"),
                new SheetRow("2026-08-02", "Temprano", new BigDecimal("20"), "EUR", "t-early", "Cuenta Principal")));

        FakeHttp.Request dataAppend = http.requests.stream()
                .filter(r -> "POST".equals(r.method()) && r.url().endsWith("'Agosto%202026'!A1:F:append?valueInputOption=RAW"))
                .findFirst().orElseThrow();
        JsonArray values = JsonParser.parseString(dataAppend.body()).getAsJsonObject().getAsJsonArray("values");
        assertEquals("2026-08-02", values.get(0).getAsJsonArray().get(0).getAsString());
        assertEquals("2026-08-15", values.get(1).getAsJsonArray().get(0).getAsString());
    }

    @Test
    void appendRowsSkipsHeaderWhenAlreadyPresent() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F1",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\",\"Cuenta\"]]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow("2026-08-01", "Nomina", new BigDecimal("100"), "EUR", "t1", "Cuenta Principal")));

        // token + metadata + ledger read + header read + data append + ledger append (no header append, no backfill)
        assertEquals(6, http.requests.size());
        // token, visible data append, ledger append
        assertEquals(3, http.requests.stream().filter(r -> "POST".equals(r.method())).count());
        assertTrue(http.requests.get(4).url().endsWith("'Agosto%202026'!A1:F:append?valueInputOption=RAW"));
    }

    @Test
    void appendRowsBackfillsCuentaHeaderOnLegacyFiveColumnSheet() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F1",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\"]]}");
        http.stub("/sheet-1/values/'Agosto%202026'!F1?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow("2026-08-01", "Nomina", new BigDecimal("100"), "EUR", "t1", "Ahorro")));

        // requests: 0=token, 1=metadata, 2=ledger read, 3=header read, 4=F1 backfill PUT, 5=data append, 6=ledger append
        assertEquals(7, http.requests.size());
        FakeHttp.Request backfill = http.requests.get(4);
        assertEquals("PUT", backfill.method());
        assertEquals("https://sheets.googleapis.com/v4/spreadsheets/sheet-1/values/'Agosto%202026'!F1?valueInputOption=RAW",
                backfill.url());
        assertEquals("{\"values\":[[\"Cuenta\"]]}", backfill.body());
        FakeHttp.Request dataAppend = http.requests.get(5);
        assertEquals("POST", dataAppend.method());
        assertTrue(dataAppend.url().endsWith("'Agosto%202026'!A1:F:append?valueInputOption=RAW"));
        assertEquals(1, JsonParser.parseString(dataAppend.body()).getAsJsonObject().getAsJsonArray("values").size());
    }

    @Test
    void appendRowsSkipsBackfillWhenCuentaHeaderIsPresent() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F1",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\",\"Otra cosa\"]]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        http.stub("/sheet-1/values/'_sync'!A2:B:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = client(http);

        client.appendRows(YearMonth.of(2026, 8), List.of(
                new SheetRow("2026-08-01", "Nomina", new BigDecimal("100"), "EUR", "t1", "Ahorro")));

        // token + metadata + ledger read + header read + data append + ledger append; no PUT to F1
        assertEquals(6, http.requests.size());
        assertTrue(http.requests.stream().noneMatch(r -> "PUT".equals(r.method())));
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
        http.stub("/sheet-1?fields=sheets.properties.title", 403, "{\"error\":{\"message\":\"permission denied\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.existingTransactionIds(YearMonth.of(2026, 8)));
        assertTrue(error.getMessage().contains("metadata failed"));
        assertTrue(error.getMessage().contains("403"));
    }

    @Test
    void nonSuccessCreateSheetThrows() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Hoja 1\"}}]}");
        http.stub("/sheet-1:batchUpdate", 500, "{\"error\":{\"message\":\"boom\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.existingTransactionIds(YearMonth.of(2026, 11)));
        assertTrue(error.getMessage().contains("add sheet failed"));
        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    void nonSuccessReadThrows() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'_sync'!A:B", 403, "{\"error\":{\"message\":\"permission denied\"}}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.existingTransactionIds(YearMonth.of(2026, 8)));
        assertTrue(error.getMessage().contains("403"));
    }

    @Test
    void nonSuccessAppendThrows() {
        FakeHttp http = SheetsTestSupport.fakeHttp();
        http.stub("/sheet-1?fields=sheets.properties.title",
                "{\"sheets\":[{\"properties\":{\"title\":\"Agosto 2026\"}},"
                        + "{\"properties\":{\"title\":\"_sync\"}}]}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F1", "{}");
        http.stub("/sheet-1/values/'Agosto%202026'!A1:F:append?valueInputOption=RAW", 500, "{\"error\":{\"message\":\"boom\"}}");
        http.stub("/sheet-1/values/'_sync'!A:B", "{}");
        GoogleSheetsClient client = client(http);

        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> client.appendRows(YearMonth.of(2026, 8), List.of(
                        new SheetRow("2026-08-01", "Nomina", new BigDecimal("1"), "EUR", "t1", "Ahorro"))));
        assertTrue(error.getMessage().contains("append failed"));
        assertTrue(error.getMessage().contains("500"));
    }

}
