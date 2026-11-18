package bank2sheets.sheets;

import bank2sheets.TestKeys;
import bank2sheets.config.SheetsConfig;
import bank2sheets.http.FakeHttp;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Shared fixtures for Google Sheets tests: a throwaway service account JSON
 * (test-only RSA key from {@link TestKeys}, used only to exercise JWT signing
 * against the fake token endpoint) plus the config and token stub that go with
 * it. Extracted so the key lives in exactly one place, reused by the client
 * tests and the Main sync end-to-end tests.
 */
public final class SheetsTestSupport {

    public static final String GOOGLE_BASE = "https://sheets.googleapis.com/v4/spreadsheets";

    private SheetsTestSupport() {
    }

    /** SheetsConfig backed by the throwaway service account and spreadsheet id "sheet-1". */
    public static SheetsConfig config() {
        return new SheetsConfig(serviceAccountB64(), "sheet-1");
    }

    /** Base64 of a throwaway service account JSON used only to exercise JWT signing. */
    public static String serviceAccountB64() {
        String pem = TestKeys.pkcs8Pem();
        String json = """
                {"type":"service_account",
                 "client_email":"bank2sheets@test.iam.gserviceaccount.com",
                 "private_key":"%s",
                 "token_uri":"https://oauth2.googleapis.com/token"}
                """.formatted(pem.replace("\n", "\\n"));
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Fake HTTP backend rooted at the Google Sheets API base URL. */
    public static FakeHttp fakeHttp() {
        return new FakeHttp(GOOGLE_BASE);
    }

    /** Stubs the OAuth token endpoint that {@link GoogleSheetsClient#accessToken()} calls. */
    public static void stubToken(FakeHttp http) {
        http.stub("https://oauth2.googleapis.com/token",
                "{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\",\"expires_in\":3599}");
    }

    /** Header row body shared by the Sheets and Main sync tests. */
    public static final String HEADER_JSON =
            "{\"values\":[[\"Fecha\",\"Importe\",\"Descripci\u00f3n\",\"Cuenta\"]]}";

    /** Stubs the spreadsheet metadata response with the given tab titles. */
    public static void stubSheetTitles(FakeHttp http, String... titles) {
        StringBuilder json = new StringBuilder("{\"sheets\":[");
        for (int i = 0; i < titles.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"properties\":{\"title\":\"").append(titles[i]).append("\"}}");
        }
        json.append("]}");
        http.stub("/sheet-1?fields=sheets.properties.title", json.toString());
    }

    /** Metadata path for the protection pass: sheet ids, titles, and existing protected ranges. */
    public static final String PROTECTION_METADATA_PATH =
            "/sheet-1?fields=sheets(properties(sheetId,title),protectedRanges)";

    /** Stubs the protection-pass metadata response with the given raw body. */
    public static void stubProtectionMetadata(FakeHttp http, String json) {
        http.stub(PROTECTION_METADATA_PATH, json);
    }

    /** Stubs the hidden _sync ledger read range with the given raw body. */
    public static void stubLedgerRead(FakeHttp http, String dataJson) {
        http.stub("/sheet-1/values/'_sync'!A:B", dataJson);
    }

    /**
     * Stubs the common "existing month tab plus created _sync ledger" state:
     * metadata, ledger read, month header, and the visible append.
     */
    public static void stubExistingMonth(FakeHttp http, String title, String headerJson) {
        stubSheetTitles(http, title, "_sync");
        stubLedgerRead(http, "{}");
        String encoded = title.replace(" ", "%20");
        http.stub("/sheet-1/values/'" + encoded + "'!A1:D1", headerJson);
        http.stub("/sheet-1/values/'" + encoded + "'!A1:D:append?valueInputOption=USER_ENTERED",
                "{\"spreadsheetId\":\"sheet-1\"}");
    }
}
