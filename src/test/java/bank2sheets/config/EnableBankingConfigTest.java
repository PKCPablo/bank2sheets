package bank2sheets.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.Bank2SheetsException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Validation tests for the Enable Banking runtime configuration. */
class EnableBankingConfigTest {

    private static final String KEY = "base64-key";

    @Test
    void acceptsLoopbackHttpRedirectWithExplicitPortAndPath() {
        EnableBankingConfig config = new EnableBankingConfig(
                "app-1", KEY, "http://127.0.0.1:8765/bank2sheets/callback");
        assertEquals("app-1", config.appId());
        assertEquals(KEY, config.privateKeyB64());
        assertEquals("http://127.0.0.1:8765/bank2sheets/callback", config.redirectUrl());
    }

    @Test
    void defaultsAspspSettingsWhenUnset() {
        EnableBankingConfig config = new EnableBankingConfig(
                "app-1", KEY, "http://127.0.0.1:8765/bank2sheets/callback");
        assertEquals("BBVA", config.aspspName());
        assertEquals("ES", config.aspspCountry());
        assertEquals("personal", config.psuType());
    }

    @Test
    void normalizesAspspSettings() {
        EnableBankingConfig config = new EnableBankingConfig(
                "app-1", KEY, "http://127.0.0.1:8765/bank2sheets/callback",
                "  Santander  ", "es", "Personal");
        assertEquals("Santander", config.aspspName());
        assertEquals("ES", config.aspspCountry());
        assertEquals("personal", config.psuType());
    }

    @Test
    void fromEnvReadsTheThreeVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("ENABLE_BANKING_APP_ID", "app-env");
        env.put("ENABLE_BANKING_PRIVATE_KEY_B64", KEY);
        env.put("ENABLE_BANKING_REDIRECT_URL", "http://localhost:9000/cb");

        EnableBankingConfig config = EnableBankingConfig.fromEnv(env);

        assertEquals("app-env", config.appId());
        assertEquals(KEY, config.privateKeyB64());
        assertEquals("http://localhost:9000/cb", config.redirectUrl());
    }

    @Test
    void fromEnvReadsTheSixVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("ENABLE_BANKING_APP_ID", "app-env");
        env.put("ENABLE_BANKING_PRIVATE_KEY_B64", KEY);
        env.put("ENABLE_BANKING_REDIRECT_URL", "http://localhost:9000/cb");
        env.put("ENABLE_BANKING_ASPSP_NAME", " Santander ");
        env.put("ENABLE_BANKING_ASPSP_COUNTRY", "fr");
        env.put("ENABLE_BANKING_PSU_TYPE", "Business");

        EnableBankingConfig config = EnableBankingConfig.fromEnv(env);

        assertEquals("Santander", config.aspspName());
        assertEquals("FR", config.aspspCountry());
        assertEquals("business", config.psuType());
    }

    @Test
    void fromEnvDefaultsAspspSettingsWhenAbsent() {
        Map<String, String> env = new HashMap<>();
        env.put("ENABLE_BANKING_APP_ID", "app-env");
        env.put("ENABLE_BANKING_PRIVATE_KEY_B64", KEY);
        env.put("ENABLE_BANKING_REDIRECT_URL", "http://localhost:9000/cb");

        EnableBankingConfig config = EnableBankingConfig.fromEnv(env);

        assertEquals("BBVA", config.aspspName());
        assertEquals("ES", config.aspspCountry());
        assertEquals("personal", config.psuType());
    }

    @Test
    void rejectsBlankAppId() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> new EnableBankingConfig("  ", KEY, "http://127.0.0.1:8765/cb"));
        assertTrue(error.getMessage().contains("ENABLE_BANKING_APP_ID"));
    }

    @Test
    void rejectsBlankPrivateKey() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> new EnableBankingConfig("app-1", "", "http://127.0.0.1:8765/cb"));
        assertTrue(error.getMessage().contains("ENABLE_BANKING_PRIVATE_KEY_B64"));
    }

    @Test
    void rejectsBlankRedirectUrl() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> new EnableBankingConfig("app-1", KEY, " "));
        assertTrue(error.getMessage().contains("ENABLE_BANKING_REDIRECT_URL"));
    }

    @Test
    void acceptsHttpsPagesRedirectUrl() {
        EnableBankingConfig config = new EnableBankingConfig(
                "app-1", KEY, "https://pkcpablo.github.io/bank2sheets/callback.html");
        assertEquals("https://pkcpablo.github.io/bank2sheets/callback.html", config.redirectUrl());
    }

    @Test
    void rejectsRedirectUrlWithoutExplicitPort() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> new EnableBankingConfig("app-1", KEY, "http://127.0.0.1/cb"));
        assertTrue(error.getMessage().contains("explicit port"));
    }

    @Test
    void acceptsHttpsRedirectWithExplicitPort() {
        EnableBankingConfig config = new EnableBankingConfig("app-1", KEY, "https://127.0.0.1:8765/cb");
        assertEquals("https://127.0.0.1:8765/cb", config.redirectUrl());
    }

    @Test
    void rejectsUnsupportedSchemeRedirectUrl() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> new EnableBankingConfig("app-1", KEY, "ftp://example.com/cb"));
        assertTrue(error.getMessage().contains("http or https scheme"));
    }

    @Test
    void rejectsHttpsRedirectUrlWithBlankHost() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> new EnableBankingConfig("app-1", KEY, "https:///cb"));
        assertTrue(error.getMessage().contains("non-blank host"));
    }

    @Test
    void rejectsExternalHostRedirectUrl() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> new EnableBankingConfig("app-1", KEY, "http://example.com:8765/cb"));
        assertTrue(error.getMessage().contains("127.0.0.1 or localhost"));
    }

    @Test
    void rejectsRedirectUrlWithEmptyPath() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> new EnableBankingConfig("app-1", KEY, "http://127.0.0.1:8765"));
        assertTrue(error.getMessage().contains("non-empty path"));
    }
}
