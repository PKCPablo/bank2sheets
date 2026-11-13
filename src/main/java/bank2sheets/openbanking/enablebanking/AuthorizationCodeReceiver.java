package bank2sheets.openbanking.enablebanking;

/**
 * Captures the OAuth authorization-code callback after the user completes SCA
 * in the bank app. Implementations either listen on a loopback HTTP server or
 * prompt the user to paste the full callback URL.
 */
@FunctionalInterface
public interface AuthorizationCodeReceiver {

    /**
     * Binds any listener needed to capture the callback <b>before</b> the
     * authorization URL is opened in the browser, so the redirect cannot hit a
     * closed port. No-op for receivers that bind lazily or need no listener.
     */
    default void start() {
    }

    /**
     * Blocks until the callback arrives (or the user pastes it) and returns the
     * full callback URL containing {@code code} and {@code state} query params.
     */
    String awaitCallbackUrl();
}
