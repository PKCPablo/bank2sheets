package bank2sheets.sheets;

import bank2sheets.TestKeys;
import bank2sheets.config.SheetsConfig;
import bank2sheets.openbanking.saltedge.FakeHttp;
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
}
