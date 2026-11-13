package bank2sheets.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.Bank2SheetsException;

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
        assertEquals("", config.enableBankingAppId());
        assertEquals("", config.enableBankingPrivateKeyB64());
        assertEquals("", config.enableBankingRedirectUrl());
        assertEquals("", config.enableBankingSessionId());
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
    void parsesSingleEnableBankingAccount() {
        assertEquals(
                List.of(new AccountConfig("enablebanking", "uuid-1")),
                AppConfig.parseAccounts("enablebanking:uuid-1"));
    }

    @Test
    void rejectsMixedProviders() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AppConfig.parseAccounts("saltedge:a, enablebanking:b"));
        assertTrue(error.getMessage().contains("mixes providers"));
        assertTrue(error.getMessage().contains("saltedge"));
        assertTrue(error.getMessage().contains("enablebanking"));
    }

    @Test
    void rejectsUnsupportedProvider() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parseAccounts("powens:acct-123"));
    }

    @Test
    void providerInfersEnableBankingWhenAccountsEmpty() {
        AppConfig config = AppConfig.fromEnv(Map.of("ENABLE_BANKING_APP_ID", "eb-app-1"));
        assertEquals("enablebanking", config.provider());
    }

    @Test
    void providerInfersSaltEdgeWhenOnlySaltEdgeCredentialsAreSet() {
        AppConfig config = AppConfig.fromEnv(Map.of("SALTEDGE_APP_ID", "app-1"));
        assertEquals("saltedge", config.provider());
    }

    @Test
    void providerPrefersEnableBankingWhenBothCredentialSetsArePresent() {
        AppConfig config = AppConfig.fromEnv(Map.of(
                "SALTEDGE_APP_ID", "app-1",
                "ENABLE_BANKING_APP_ID", "eb-app-1"));
        assertEquals("enablebanking", config.provider());
    }

    @Test
    void providerFailsWithoutAccountsOrCredentials() {
        Bank2SheetsException error = assertThrows(Bank2SheetsException.class,
                () -> AppConfig.fromEnv(Map.of()).provider());
        assertTrue(error.getMessage().contains("BANK2SHEETS_ACCOUNTS"));
        assertTrue(error.getMessage().contains("ENABLE_BANKING_APP_ID"));
        assertTrue(error.getMessage().contains("SALTEDGE_APP_ID"));
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

    @Test
    void readsAllEnableBankingValuesFromEnvironment() {
        AppConfig config = AppConfig.fromEnv(Map.of(
                "ENABLE_BANKING_APP_ID", "eb-app-1",
                "ENABLE_BANKING_PRIVATE_KEY_B64", "a2V5",
                "ENABLE_BANKING_REDIRECT_URL", "http://127.0.0.1:8765/bank2sheets/callback",
                "ENABLE_BANKING_SESSION_ID", "sess-1",
                "BANK2SHEETS_ACCOUNTS", "enablebanking:uuid-1"));
        assertEquals("eb-app-1", config.enableBankingAppId());
        assertEquals("a2V5", config.enableBankingPrivateKeyB64());
        assertEquals("http://127.0.0.1:8765/bank2sheets/callback", config.enableBankingRedirectUrl());
        assertEquals("sess-1", config.enableBankingSessionId());
        assertEquals(List.of(new AccountConfig("enablebanking", "uuid-1")), config.accounts());
    }
}
