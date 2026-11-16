package bank2sheets.config;

import bank2sheets.Bank2SheetsException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runtime configuration assembled from environment variables. Credentials and
 * connection state are read at runtime and never stored in the repository; the
 * variable names are documented in the README configuration table.
 *
 * <p>{@code BANK2SHEETS_ACCOUNTS} holds comma-separated {@code provider:accountId[:label]}
 * entries; all entries must use the {@code enablebanking} provider.
 *
 * <p>The optional {@code ENABLE_BANKING_ASPSP_NAME}, {@code ENABLE_BANKING_ASPSP_COUNTRY}
 * and {@code ENABLE_BANKING_PSU_TYPE} variables configure the target ASPSP; blank values
 * are defaulted/normalized by {@link EnableBankingConfig} (BBVA/ES/personal).
 */
public record AppConfig(
        String enableBankingAppId,
        String enableBankingPrivateKeyB64,
        String enableBankingRedirectUrl,
        String enableBankingSessionId,
        String enableBankingAspspName,
        String enableBankingAspspCountry,
        String enableBankingPsuType,
        List<AccountConfig> accounts) {

    /** Convenience constructor defaulting the ASPSP settings (blank -> BBVA/ES/personal). */
    public AppConfig(String enableBankingAppId, String enableBankingPrivateKeyB64,
            String enableBankingRedirectUrl, String enableBankingSessionId,
            List<AccountConfig> accounts) {
        this(enableBankingAppId, enableBankingPrivateKeyB64, enableBankingRedirectUrl,
                enableBankingSessionId, "", "", "", accounts);
    }

    public AppConfig {
        accounts = List.copyOf(accounts);
    }

    public static AppConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    /** Returns a copy with the persisted Enable Banking session id replaced. */
    public AppConfig withProviderState(String sessionId) {
        return new AppConfig(enableBankingAppId, enableBankingPrivateKeyB64,
                enableBankingRedirectUrl, sessionId, enableBankingAspspName,
                enableBankingAspspCountry, enableBankingPsuType, accounts);
    }

    /**
     * The provider to use: the first entry of {@code BANK2SHEETS_ACCOUNTS} when
     * accounts are configured, otherwise inferred from the Enable Banking
     * credentials. Throws when neither accounts nor credentials are configured.
     */
    public String provider() {
        if (!accounts.isEmpty()) {
            return accounts.get(0).provider();
        }
        if (!enableBankingAppId.isBlank()) {
            return "enablebanking";
        }
        throw new Bank2SheetsException(
                "no accounts or provider credentials configured; set BANK2SHEETS_ACCOUNTS "
                        + "(e.g. enablebanking:<uuid>), or provider credentials (ENABLE_BANKING_APP_ID)");
    }

    /** The Enable Banking session id field (blank when unset). */
    public String providerId() {
        return enableBankingSessionId;
    }

    /** Enable Banking credentials validated by {@link EnableBankingConfig} (throws when blank). */
    public EnableBankingConfig enableBankingConfig() {
        return new EnableBankingConfig(enableBankingAppId, enableBankingPrivateKeyB64,
                enableBankingRedirectUrl, enableBankingAspspName, enableBankingAspspCountry,
                enableBankingPsuType);
    }

    static AppConfig fromEnv(Map<String, String> env) {
        return new AppConfig(
                env.getOrDefault("ENABLE_BANKING_APP_ID", ""),
                env.getOrDefault("ENABLE_BANKING_PRIVATE_KEY_B64", ""),
                env.getOrDefault("ENABLE_BANKING_REDIRECT_URL", ""),
                env.getOrDefault("ENABLE_BANKING_SESSION_ID", ""),
                env.getOrDefault("ENABLE_BANKING_ASPSP_NAME", ""),
                env.getOrDefault("ENABLE_BANKING_ASPSP_COUNTRY", ""),
                env.getOrDefault("ENABLE_BANKING_PSU_TYPE", ""),
                parseAccounts(env.getOrDefault("BANK2SHEETS_ACCOUNTS", "")));
    }

    /**
     * Parses {@code provider:accountId[:label]} entries separated by commas.
     * The optional label is trimmed and may contain spaces or colons (commas
     * still separate entries). Empty entries are ignored; malformed entries and
     * unsupported providers fail fast.
     */
    static List<AccountConfig> parseAccounts(String raw) {
        List<AccountConfig> accounts = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":", 3);
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid BANK2SHEETS_ACCOUNTS entry (expected provider:accountId[:label]): " + entry);
            }
            String provider = parts[0];
            String accountId = parts[1];
            String label = parts.length == 3 ? parts[2].trim() : "";
            if (!"enablebanking".equals(provider)) {
                throw new IllegalArgumentException("Unsupported provider: " + provider);
            }
            accounts.add(new AccountConfig(provider, accountId, label));
        }
        return List.copyOf(accounts);
    }
}
