package bank2sheets.sheets;

import bank2sheets.config.SheetsConfig;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;

/**
 * Shared fixtures for Google Sheets tests: a throwaway service account JSON
 * (test-only RSA key used only to exercise JWT signing against the fake token
 * endpoint) plus the config and token stub that go with it. Extracted so the
 * key lives in exactly one place, reused by the client tests and the Main sync
 * end-to-end tests.
 */
public final class SheetsTestSupport {

    /**
     * Throwaway RSA key (not a real credential) generated at runtime; it is only
     * used to exercise JWT signing against the fake token endpoint, which never
     * verifies the signature.
     */
    private static final PrivateKey TEST_PRIVATE_KEY = generateTestPrivateKey();

    private SheetsTestSupport() {
    }

    private static PrivateKey generateTestPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair().getPrivate();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate test RSA key", e);
        }
    }

    /** SheetsConfig backed by the throwaway service account and spreadsheet id "sheet-1". */
    public static SheetsConfig config() {
        return new SheetsConfig(serviceAccountB64(), "sheet-1");
    }

    /** Base64 of a throwaway service account JSON used only to exercise JWT signing. */
    public static String serviceAccountB64() {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(TEST_PRIVATE_KEY.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        String json = """
                {"type":"service_account",
                 "client_email":"bank2sheets@test.iam.gserviceaccount.com",
                 "private_key":"%s",
                 "token_uri":"https://oauth2.googleapis.com/token"}
                """.formatted(pem.replace("\n", "\\n"));
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Stubs the OAuth token endpoint that {@link GoogleSheetsClient#accessToken()} calls. */
    public static void stubToken(FakeSheetsHttp http) {
        http.stub("https://oauth2.googleapis.com/token",
                "{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\",\"expires_in\":3599}");
    }
}
