package bank2sheets.openbanking;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.Bank2SheetsException;
import bank2sheets.TestKeys;
import bank2sheets.config.AccountConfig;
import bank2sheets.config.AppConfig;
import bank2sheets.openbanking.enablebanking.EnableBankingClient;
import bank2sheets.openbanking.saltedge.SaltEdgeClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Direct coverage for the {@link ProviderSources} factory and its credential guards. */
class ProviderSourcesTest {

    private static final List<AccountConfig> SALT_EDGE_ACCOUNTS =
            List.of(new AccountConfig("saltedge", "ACCT_ID"));
    private static final List<AccountConfig> ENABLE_BANKING_ACCOUNTS =
            List.of(new AccountConfig("enablebanking", "SESSION_UUID"));

    @Test
    void saltEdgeRequiresAppIdAndSecret() {
        AppConfig cfg = new AppConfig("", "", "", "", "", "", "", "", SALT_EDGE_ACCOUNTS);
        Bank2SheetsException ex = assertThrows(Bank2SheetsException.class, () -> ProviderSources.create(cfg));
        assertTrue(ex.getMessage().contains("SALTEDGE_APP_ID"));
        assertTrue(ex.getMessage().contains("SALTEDGE_SECRET"));
    }

    @Test
    void saltEdgeReturnsSaltEdgeClient() {
        AppConfig cfg = new AppConfig("app-id", "secret", "customer", "conn", "", "", "", "",
                SALT_EDGE_ACCOUNTS);
        assertInstanceOf(SaltEdgeClient.class, ProviderSources.create(cfg));
    }

    @Test
    void enableBankingRequiresAppId() {
        AppConfig cfg = new AppConfig("", "", "", "", "", TestKeys.base64Pem(),
                "http://127.0.0.1:8765/bank2sheets/callback", "", ENABLE_BANKING_ACCOUNTS);
        Bank2SheetsException ex = assertThrows(Bank2SheetsException.class, () -> ProviderSources.create(cfg));
        assertTrue(ex.getMessage().contains("ENABLE_BANKING_APP_ID"));
    }

    @Test
    void enableBankingRequiresPrivateKey() {
        AppConfig cfg = new AppConfig("", "", "", "", "app-1", "",
                "http://127.0.0.1:8765/bank2sheets/callback", "", ENABLE_BANKING_ACCOUNTS);
        Bank2SheetsException ex = assertThrows(Bank2SheetsException.class, () -> ProviderSources.create(cfg));
        assertTrue(ex.getMessage().contains("ENABLE_BANKING_PRIVATE_KEY_B64"));
    }

    @Test
    void enableBankingRequiresRedirectUrl() {
        AppConfig cfg = new AppConfig("", "", "", "", "app-1", TestKeys.base64Pem(), "", "",
                ENABLE_BANKING_ACCOUNTS);
        Bank2SheetsException ex = assertThrows(Bank2SheetsException.class, () -> ProviderSources.create(cfg));
        assertTrue(ex.getMessage().contains("ENABLE_BANKING_REDIRECT_URL"));
    }

    @Test
    void enableBankingReturnsClientAndSetsSession() {
        AppConfig cfg = new AppConfig("", "", "", "", "app-1", TestKeys.base64Pem(),
                "http://127.0.0.1:8765/bank2sheets/callback", "session-1", ENABLE_BANKING_ACCOUNTS);
        assertInstanceOf(EnableBankingClient.class, ProviderSources.create(cfg));
    }

    @Test
    void enableBankingAcceptsHttpsRedirectUrl() {
        AppConfig cfg = new AppConfig("", "", "", "", "app-1", TestKeys.base64Pem(),
                "https://pkcpablo.github.io/bank2sheets/callback.html", "", ENABLE_BANKING_ACCOUNTS);
        assertInstanceOf(EnableBankingClient.class, ProviderSources.create(cfg));
    }

    @Test
    void unknownProviderFails() {
        AppConfig cfg = new AppConfig("", "", "", "", "", "", "", "",
                List.of(new AccountConfig("powens", "x")));
        Bank2SheetsException ex = assertThrows(Bank2SheetsException.class, () -> ProviderSources.create(cfg));
        assertTrue(ex.getMessage().contains("Unsupported provider"));
    }

}
