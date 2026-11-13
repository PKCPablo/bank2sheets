package bank2sheets.openbanking.enablebanking;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Captures the OAuth callback by prompting the user to paste the full callback
 * URL from the browser. Used for HTTPS redirect URLs (e.g. the GitHub Pages
 * callback page) where no local loopback listener can be bound. The pasted
 * URL is never echoed or logged.
 */
public final class PasteAuthorizationCodeReceiver implements AuthorizationCodeReceiver {

    private final Supplier<String> lineSupplier;

    /** Reads one line from the console when available, else from stdin (UTF-8). */
    public PasteAuthorizationCodeReceiver() {
        this(defaultSupplier());
    }

    /** Testable constructor: {@code lineSupplier} provides the pasted line. */
    public PasteAuthorizationCodeReceiver(Supplier<String> lineSupplier) {
        this.lineSupplier = lineSupplier;
    }

    @Override
    public String awaitCallbackUrl() {
        System.out.println("Paste the full callback URL from the browser:");
        String line = lineSupplier.get();
        if (line == null) {
            throw new EnableBankingException("No callback URL was pasted (input stream closed)");
        }
        String url = line.trim();
        if (url.isBlank()) {
            throw new EnableBankingException("Pasted callback URL is blank; copy the full URL from the browser");
        }
        return url;
    }

    private static Supplier<String> defaultSupplier() {
        java.io.Console console = System.console();
        if (console != null) {
            return () -> console.readLine();
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return () -> {
            try {
                return reader.readLine();
            } catch (java.io.IOException e) {
                throw new EnableBankingException("Failed to read the pasted callback URL", e);
            }
        };
    }
}
