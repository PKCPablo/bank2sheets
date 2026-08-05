package bank2sheets.config;

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
 *   <li>{@code SALTEDGE_CONNECTION_ID} - active connection, persisted by the {@code connect} command</li>
 *   <li>{@code BANK2SHEETS_ACCOUNTS} - comma-separated {@code provider:accountId} entries to fetch</li>
 * </ul>
 */
public record AppConfig(
        String saltEdgeAppId,
        String saltEdgeSecret,
        String saltEdgeCustomerId,
        String saltEdgeConnectionId,
        List<AccountConfig> accounts) {

    public static AppConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    /** Returns a copy with the connection id replaced (used to merge persisted state). */
    public AppConfig withConnectionId(String connectionId) {
        return new AppConfig(saltEdgeAppId, saltEdgeSecret, saltEdgeCustomerId, connectionId, accounts);
    }

    static AppConfig fromEnv(Map<String, String> env) {
        return new AppConfig(
                env.getOrDefault("SALTEDGE_APP_ID", ""),
                env.getOrDefault("SALTEDGE_SECRET", ""),
                env.getOrDefault("SALTEDGE_CUSTOMER_ID", ""),
                env.getOrDefault("SALTEDGE_CONNECTION_ID", ""),
                parseAccounts(env.getOrDefault("BANK2SHEETS_ACCOUNTS", "")));
    }

    /**
     * Parses {@code provider:accountId} entries separated by commas. Empty
     * entries are ignored; malformed entries and unsupported providers fail fast.
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
            if (!"saltedge".equals(provider)) {
                throw new IllegalArgumentException("Unsupported provider: " + provider);
            }
            accounts.add(new AccountConfig(provider, accountId));
        }
        return List.copyOf(accounts);
    }
}
