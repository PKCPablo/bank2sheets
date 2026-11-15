package bank2sheets.openbanking;

import bank2sheets.Bank2SheetsException;
import bank2sheets.config.AppConfig;
import bank2sheets.http.RealHttpExchange;
import bank2sheets.openbanking.enablebanking.AuthorizationCodeReceiver;
import bank2sheets.openbanking.enablebanking.EnableBankingClient;
import bank2sheets.openbanking.enablebanking.LoopbackAuthorizationCodeReceiver;
import bank2sheets.openbanking.enablebanking.PasteAuthorizationCodeReceiver;
import java.net.URI;

/**
 * Selects the {@link TransactionSource} for the single provider configured in
 * {@code BANK2SHEETS_ACCOUNTS} and validates only that provider's required
 * credentials. Enable Banking is the only supported provider (ADR-0005).
 */
public final class ProviderSources {

    private ProviderSources() {
    }

    /** Builds the provider client backed by the shared {@link RealHttpExchange} seam. */
    public static TransactionSource create(AppConfig cfg) {
        if (!"enablebanking".equals(cfg.provider())) {
            throw new Bank2SheetsException("Unsupported provider: " + cfg.provider());
        }
        // EnableBankingConfig validates the three credentials and the redirect URL
        // (http loopback for sandbox, https paste fallback for production).
        AuthorizationCodeReceiver receiver = cfg.enableBankingRedirectUrl().toLowerCase().startsWith("https://")
                ? new PasteAuthorizationCodeReceiver()
                : new LoopbackAuthorizationCodeReceiver(URI.create(cfg.enableBankingRedirectUrl()));
        EnableBankingClient client = new EnableBankingClient(cfg.enableBankingConfig(),
                new RealHttpExchange(),
                receiver);
        if (!cfg.enableBankingSessionId().isBlank()) {
            client.setSessionId(cfg.enableBankingSessionId());
        }
        return client;
    }
}
