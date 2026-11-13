package bank2sheets.openbanking;

import bank2sheets.Bank2SheetsException;
import bank2sheets.config.AppConfig;
import bank2sheets.http.RealHttpExchange;
import bank2sheets.openbanking.enablebanking.EnableBankingClient;
import bank2sheets.openbanking.enablebanking.LoopbackAuthorizationCodeReceiver;
import bank2sheets.openbanking.saltedge.SaltEdgeClient;
import java.net.URI;

/**
 * Selects the {@link TransactionSource} for the single provider configured in
 * {@code BANK2SHEETS_ACCOUNTS} and validates only that provider's required
 * credentials. Salt Edge remains a selectable fallback; Enable Banking is the
 * target provider (ADR-0005).
 */
public final class ProviderSources {

    private ProviderSources() {
    }

    /** Builds the provider client backed by the shared {@link RealHttpExchange} seam. */
    public static TransactionSource create(AppConfig cfg) {
        return switch (cfg.provider()) {
            case "saltedge" -> saltEdge(cfg);
            case "enablebanking" -> enableBanking(cfg);
            default -> throw new Bank2SheetsException("Unsupported provider: " + cfg.provider());
        };
    }

    private static TransactionSource saltEdge(AppConfig cfg) {
        if (cfg.saltEdgeAppId().isBlank() || cfg.saltEdgeSecret().isBlank()) {
            throw new Bank2SheetsException(
                    "SALTEDGE_APP_ID and SALTEDGE_SECRET must be set in the environment");
        }
        return SaltEdgeClient.create(cfg);
    }

    private static TransactionSource enableBanking(AppConfig cfg) {
        // EnableBankingConfig validates the three credentials and the loopback redirect URL.
        EnableBankingClient client = new EnableBankingClient(cfg.enableBankingConfig(),
                new RealHttpExchange(),
                new LoopbackAuthorizationCodeReceiver(URI.create(cfg.enableBankingRedirectUrl())));
        if (!cfg.enableBankingSessionId().isBlank()) {
            client.setSessionId(cfg.enableBankingSessionId());
        }
        return client;
    }
}
