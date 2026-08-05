package bank2sheets.sheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.config.SheetsConfig;
import bank2sheets.openbanking.saltedge.FakeHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

    @Test
    void existingTransactionIdsSkipsHeaderAndParsesColumnE() {
        FakeSheetsHttp http = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(http);
        http.stub("/sheet-1/values/E2:E",
                """
                {"range":"Sheet1!E2:E","majorDimension":"ROWS","values":[["t1"],["t2"],[""]]}
                """);
        GoogleSheetsClient client = new GoogleSheetsClient(SheetsTestSupport.config(), http);

        assertEquals(Set.of("t1", "t2"), client.existingTransactionIds());
    }

    @Test
    void existingTransactionIdsIsEmptyWhenSheetHasNoData() {
        FakeSheetsHttp http = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(http);
        http.stub("/sheet-1/values/E2:E", "{}");
        GoogleSheetsClient client = new GoogleSheetsClient(SheetsTestSupport.config(), http);

        assertTrue(client.existingTransactionIds().isEmpty());
    }

    @Test
    void tokenRequestUsesJwtBearerGrantAndCachesToken() {
        FakeSheetsHttp http = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(http);
        http.stub("/sheet-1/values/E2:E", "{}");
        GoogleSheetsClient client = new GoogleSheetsClient(SheetsTestSupport.config(), http);

        client.existingTransactionIds();
        client.existingTransactionIds();

        long tokenRequests = http.requests().stream()
                .filter(r -> r.url().equals("https://oauth2.googleapis.com/token"))
                .count();
        assertEquals(1, tokenRequests);
        FakeHttp.Request token = http.requests().get(0);
        assertEquals("POST", token.method());
        assertTrue(token.body().startsWith(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="));
        String assertion = token.body().substring(token.body().indexOf("assertion=") + "assertion=".length());
        assertEquals(3, assertion.split("\\.").length);
        FakeHttp.Request read = http.requests().get(1);
        assertEquals("Bearer tok-123", read.headers().get("Authorization"));
    }

    @Test
    void appendRowsWritesHeaderThenDataWithAmountsAsNumbers() {
        FakeSheetsHttp http = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(http);
        http.stub("/sheet-1/values/A1:E1", "{}");
        http.stub("/sheet-1/values/A1:E:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = new GoogleSheetsClient(SheetsTestSupport.config(), http);

        client.appendRows(List.of(
                new SheetRow("2026-08-01", "Nomina", new BigDecimal("1200.50"), "EUR", "t1"),
                new SheetRow("2026-08-02", "Cafe", new BigDecimal("-3.20"), "EUR", "t2")));

        // requests: 0=token, 1=header read, 2=header append, 3=data append
        assertEquals(4, http.requests().size());
        FakeHttp.Request headerAppend = http.requests().get(2);
        assertEquals("POST", headerAppend.method());
        assertTrue(headerAppend.url().endsWith(":append?valueInputOption=RAW"));
        assertEquals("{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\"]]}",
                headerAppend.body());

        FakeHttp.Request dataAppend = http.requests().get(3);
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
        assertEquals(new BigDecimal("-3.20"), values.get(1).getAsJsonArray().get(2).getAsBigDecimal());
    }

    @Test
    void appendRowsSkipsHeaderWhenAlreadyPresent() {
        FakeSheetsHttp http = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(http);
        http.stub("/sheet-1/values/A1:E1",
                "{\"values\":[[\"Fecha\",\"Descripción\",\"Importe\",\"Moneda\",\"TransactionId\"]]}");
        http.stub("/sheet-1/values/A1:E:append?valueInputOption=RAW", "{\"spreadsheetId\":\"sheet-1\"}");
        GoogleSheetsClient client = new GoogleSheetsClient(SheetsTestSupport.config(), http);

        client.appendRows(List.of(new SheetRow("2026-08-01", "Nomina", new BigDecimal("100"), "EUR", "t1")));

        // token + header read + one data append (no header append)
        assertEquals(3, http.requests().size());
        assertEquals(2, http.requests().stream().filter(r -> "POST".equals(r.method())).count());
        assertTrue(http.requests().get(2).url().endsWith(":append?valueInputOption=RAW"));
    }

    @Test
    void appendRowsWithNoRowsDoesNothing() {
        FakeSheetsHttp http = new FakeSheetsHttp();
        GoogleSheetsClient client = new GoogleSheetsClient(SheetsTestSupport.config(), http);

        client.appendRows(List.of());

        assertTrue(http.requests().isEmpty());
    }

    @Test
    void createFailsFastWithoutServiceAccountConfig() {
        SheetsException error = assertThrows(SheetsException.class,
                () -> GoogleSheetsClient.create(new SheetsConfig("", "sheet-1")));
        assertTrue(error.getMessage().contains("GOOGLE_SERVICE_ACCOUNT_B64"));
    }

    @Test
    void createFailsFastWithoutSpreadsheetConfig() {
        SheetsException error = assertThrows(SheetsException.class,
                () -> GoogleSheetsClient.create(new SheetsConfig(SheetsTestSupport.serviceAccountB64(), "")));
        assertTrue(error.getMessage().contains("GOOGLE_SPREADSHEET_ID"));
    }

    @Test
    void invalidServiceAccountBase64Fails() {
        FakeSheetsHttp http = new FakeSheetsHttp();
        GoogleSheetsClient client = new GoogleSheetsClient(new SheetsConfig("not-base64!!", "sheet-1"), http);

        SheetsException error = assertThrows(SheetsException.class, client::existingTransactionIds);
        assertTrue(error.getMessage().contains("GOOGLE_SERVICE_ACCOUNT_B64"));
    }

    @Test
    void serviceAccountWithoutCredentialsFails() {
        FakeSheetsHttp http = new FakeSheetsHttp();
        String b64 = Base64.getEncoder()
                .encodeToString("{\"type\":\"service_account\"}".getBytes(StandardCharsets.UTF_8));
        GoogleSheetsClient client = new GoogleSheetsClient(new SheetsConfig(b64, "sheet-1"), http);

        SheetsException error = assertThrows(SheetsException.class, client::existingTransactionIds);
        assertTrue(error.getMessage().contains("client_email"));
    }

    @Test
    void nonSuccessReadThrows() {
        FakeSheetsHttp http = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(http);
        http.stub("/sheet-1/values/E2:E", 403, "{\"error\":{\"message\":\"permission denied\"}}");
        GoogleSheetsClient client = new GoogleSheetsClient(SheetsTestSupport.config(), http);

        SheetsException error = assertThrows(SheetsException.class, client::existingTransactionIds);
        assertTrue(error.getMessage().contains("403"));
    }

    @Test
    void nonSuccessAppendThrows() {
        FakeSheetsHttp http = new FakeSheetsHttp();
        SheetsTestSupport.stubToken(http);
        http.stub("/sheet-1/values/A1:E1", "{}");
        http.stub("/sheet-1/values/A1:E:append?valueInputOption=RAW", 500, "{\"error\":{\"message\":\"boom\"}}");
        GoogleSheetsClient client = new GoogleSheetsClient(SheetsTestSupport.config(), http);

        SheetsException error = assertThrows(SheetsException.class,
                () -> client.appendRows(List.of(new SheetRow("2026-08-01", "Nomina", new BigDecimal("1"), "EUR", "t1"))));
        assertTrue(error.getMessage().contains("append failed"));
        assertTrue(error.getMessage().contains("500"));
    }

}
