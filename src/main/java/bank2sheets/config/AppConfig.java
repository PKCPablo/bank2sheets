package bank2sheets.config;

import bank2sheets.Bank2SheetsException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runtime configuration assembled from environment variables. Credentials and
 * connection state are read at runtime and never stored in the repository.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code SALTEDGE_APP_ID}, {@code SALTEDGE_SECRET} - Salt Edge API key (App-id/Secret headers)</li>
 *   <li>{@code SALTEDGE_CUSTOMER_ID} - Salt Edge customer that owns the connection</li>
 *   <li>{@code SALTEDGE_CONNECTION_ID} - active Salt Edge connection, persisted by {@code connect}</li>
 *   <li>{@code ENABLE_BANKING_APP_ID}, {@code ENABLE_BANKING_PRIVATE_KEY_B64},
 *       {@code ENABLE_BANKING_REDIRECT_URL} - Enable Banking application credentials</li>
 *   <li>{@code ENABLE_BANKING_SESSION_ID} - active Enable Banking session, persisted by {@code connect}</li>
 *   <li>{@code BANK2SHEETS_ACCOUNTS} - comma-separated {@code provider:accountId} entries to fetch
 *       ({@code saltedge:<id>} or {@code enablebanking:<uuid>}); all entries must share one provider</li>
 * </ul>
 */
public record AppConfig(
        String saltEdgeAppId,
        String saltEdgeSecret,
        String saltEdgeCustomerId,
        String saltEdgeConnectionId,
        String enableBankingAppId,
        String enableBankingPrivateKeyB64,
        String enableBankingRedirectUrl,
        String enableBankingSessionId,
        List<AccountConfig> accounts) {

    public AppConfig {
        accounts = List.copyOf(accounts);
    }

    /** Five-argument convenience constructor defaulting the Enable Banking fields to blank. */
    public AppConfig(String saltEdgeAppId, String saltEdgeSecret, String saltEdgeCustomerId,
            String saltEdgeConnectionId, List<AccountConfig> accounts) {
        this(saltEdgeAppId, saltEdgeSecret, saltEdgeCustomerId, saltEdgeConnectionId,
                "", "", "", "", accounts);
    }

    public static AppConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    /**
     * Returns a copy with the provider's connection/session id replaced
     * (used to merge persisted state); unknown providers are returned as-is.
     */
    public AppConfig withProviderState(String provider, String id) {
        return switch (provider) {
            case "saltedge" -> new AppConfig(saltEdgeAppId, saltEdgeSecret, saltEdgeCustomerId, id,
                    enableBankingAppId, enableBankingPrivateKeyB64, enableBankingRedirectUrl,
                    enableBankingSessionId, accounts);
            case "enablebanking" -> new AppConfig(saltEdgeAppId, saltEdgeSecret, saltEdgeCustomerId,
                    saltEdgeConnectionId, enableBankingAppId, enableBankingPrivateKeyB64,
                    enableBankingRedirectUrl, id, accounts);
            default -> this;
        };
    }

    /**
     * The provider to use: the first entry of {@code BANK2SHEETS_ACCOUNTS} when
     * accounts are configured, otherwise inferred from credentials (Enable
     * Banking wins when both credential sets are present). Throws when neither
     * accounts nor any provider credentials are configured.
     */
    public String provider() {
        if (!accounts.isEmpty()) {
            return accounts.get(0).provider();
        }
        if (!enableBankingAppId.isBlank()) {
            return "enablebanking";
        }
        if (!saltEdgeAppId.isBlank()) {
            return "saltedge";
        }
        throw new Bank2SheetsException(
                "no accounts or provider credentials configured; set BANK2SHEETS_ACCOUNTS "
                        + "(e.g. saltedge:ACCT_ID or enablebanking:<uuid>), or provider credentials "
                        + "(ENABLE_BANKING_APP_ID or SALTEDGE_APP_ID)");
    }

    /** The connection/session id field of the configured provider (blank when unset). */
    public String providerId() {
        return switch (provider()) {
            case "saltedge" -> saltEdgeConnectionId;
            case "enablebanking" -> enableBankingSessionId;
            default -> "";
        };
    }

    /** Enable Banking credentials validated by {@link EnableBankingConfig} (throws when blank). */
    public EnableBankingConfig enableBankingConfig() {
        return new EnableBankingConfig(enableBankingAppId, enableBankingPrivateKeyB64, enableBankingRedirectUrl);
    }

    static AppConfig fromEnv(Map<String, String> env) {
        return new AppConfig(
                env.getOrDefault("SALTEDGE_APP_ID", ""),
                env.getOrDefault("SALTEDGE_SECRET", ""),
                env.getOrDefault("SALTEDGE_CUSTOMER_ID", ""),
                env.getOrDefault("SALTEDGE_CONNECTION_ID", ""),
                env.getOrDefault("ENABLE_BANKING_APP_ID", ""),
                env.getOrDefault("ENABLE_BANKING_PRIVATE_KEY_B64", ""),
                env.getOrDefault("ENABLE_BANKING_REDIRECT_URL", ""),
                env.getOrDefault("ENABLE_BANKING_SESSION_ID", ""),
                parseAccounts(env.getOrDefault("BANK2SHEETS_ACCOUNTS", "")));
    }

    /**
     * Parses {@code provider:accountId} entries separated by commas. Empty
     * entries are ignored; malformed entries, unsupported providers, and mixed
     * providers in one list fail fast.
     */
    static List<AccountConfig> parseAccounts(String raw) {
        List<AccountConfig> accounts = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid BANK2SHEETS_ACCOUNTS entry (expected provider:accountId): " + entry);
            }
            String provider = trimmed.substring(0, colon);
            String accountId = trimmed.substring(colon + 1);
            if (!"saltedge".equals(provider) && !"enablebanking".equals(provider)) {
                throw new IllegalArgumentException("Unsupported provider: " + provider);
            }
            if (!accounts.isEmpty() && !accounts.get(0).provider().equals(provider)) {
                throw new IllegalArgumentException(
                        "BANK2SHEETS_ACCOUNTS mixes providers (" + accounts.get(0).provider() + " and "
                                + provider + "); all entries must use one provider per invocation");
            }
            accounts.add(new AccountConfig(provider, accountId));
        }
        return List.copyOf(accounts);
    }
}
