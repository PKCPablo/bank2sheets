package bank2sheets.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void emptyEnvironmentYieldsEmptyConfig() {
        AppConfig config = AppConfig.fromEnv(Map.of());
        assertEquals("", config.saltEdgeAppId());
        assertEquals("", config.saltEdgeSecret());
        assertEquals("", config.saltEdgeCustomerId());
        assertEquals("", config.saltEdgeConnectionId());
        assertTrue(config.accounts().isEmpty());
    }

    @Test
    void parsesSingleSaltEdgeAccount() {
        assertEquals(
                List.of(new AccountConfig("saltedge", "acct-123")),
                AppConfig.parseAccounts("saltedge:acct-123"));
    }

    @Test
    void parsesMultipleAccountsAndToleratesWhitespace() {
        assertEquals(
                List.of(
                        new AccountConfig("saltedge", "acct-1"),
                        new AccountConfig("saltedge", "acct-2")),
                AppConfig.parseAccounts(" saltedge:acct-1 , saltedge:acct-2 ,"));
    }

    @Test
    void rejectsEntryWithoutProviderSeparator() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parseAccounts("acct-123"));
    }

    @Test
    void rejectsEntryWithMissingAccountId() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parseAccounts("saltedge:"));
    }

    @Test
    void rejectsUnsupportedProvider() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parseAccounts("powens:acct-123"));
    }

    @Test
    void readsAllSaltEdgeValuesFromEnvironment() {
        AppConfig config = AppConfig.fromEnv(Map.of(
                "SALTEDGE_APP_ID", "app-1",
                "SALTEDGE_SECRET", "secret-1",
                "SALTEDGE_CUSTOMER_ID", "cust-1",
                "SALTEDGE_CONNECTION_ID", "conn-1",
                "BANK2SHEETS_ACCOUNTS", "saltedge:acct-1"));
        assertEquals("app-1", config.saltEdgeAppId());
        assertEquals("secret-1", config.saltEdgeSecret());
        assertEquals("cust-1", config.saltEdgeCustomerId());
        assertEquals("conn-1", config.saltEdgeConnectionId());
        assertEquals(List.of(new AccountConfig("saltedge", "acct-1")), config.accounts());
    }
}
