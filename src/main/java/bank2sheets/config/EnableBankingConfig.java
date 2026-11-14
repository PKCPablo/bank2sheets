package bank2sheets.config;

import bank2sheets.Bank2SheetsException;
import java.net.URI;
import java.util.Map;

/**
 * Runtime configuration for the Enable Banking adapter, read from environment
 * variables at runtime and never stored in the repository; the variable names
 * are documented in the README configuration table.
 *
 * <p>The private key is a base64-encoded PKCS#8 PEM ({@code BEGIN PRIVATE KEY});
 * convert PKCS#1 keys with {@code openssl pkcs8 -topk8 -nocrypt}. The redirect URL
 * is validated in two modes: {@code http} loopback ({@code 127.0.0.1}/{@code localhost},
 * explicit port, non-empty path) or {@code https} (non-blank host, non-empty path).
 */
public record EnableBankingConfig(String appId, String privateKeyB64, String redirectUrl) {

    public EnableBankingConfig {
        if (appId == null || appId.isBlank()) {
            throw new Bank2SheetsException("ENABLE_BANKING_APP_ID must be set (Enable Banking application id)");
        }
        if (privateKeyB64 == null || privateKeyB64.isBlank()) {
            throw new Bank2SheetsException(
                    "ENABLE_BANKING_PRIVATE_KEY_B64 must be set (base64 PKCS#8 PEM private key)");
        }
        if (redirectUrl == null || redirectUrl.isBlank()) {
            throw new Bank2SheetsException("ENABLE_BANKING_REDIRECT_URL must be set (application redirect URL)");
        }
        validateRedirectUrl(redirectUrl);
    }

    public static EnableBankingConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    public static EnableBankingConfig fromEnv(Map<String, String> env) {
        return new EnableBankingConfig(
                env.getOrDefault("ENABLE_BANKING_APP_ID", ""),
                env.getOrDefault("ENABLE_BANKING_PRIVATE_KEY_B64", ""),
                env.getOrDefault("ENABLE_BANKING_REDIRECT_URL", ""));
    }

    /** Validates the redirect URL per the two modes described in the class Javadoc. */
    private static void validateRedirectUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new Bank2SheetsException("ENABLE_BANKING_REDIRECT_URL is not a valid URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new Bank2SheetsException(
                    "ENABLE_BANKING_REDIRECT_URL must use the http or https scheme: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new Bank2SheetsException(
                    "ENABLE_BANKING_REDIRECT_URL must have a non-blank host: " + url);
        }
        if ("http".equalsIgnoreCase(scheme)) {
            if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
                throw new Bank2SheetsException(
                        "ENABLE_BANKING_REDIRECT_URL must point at 127.0.0.1 or localhost: " + url);
            }
            int port = uri.getPort();
            if (port < 1 || port > 65535) {
                throw new Bank2SheetsException(
                        "ENABLE_BANKING_REDIRECT_URL must carry an explicit port in 1..65535: " + url);
            }
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            throw new Bank2SheetsException(
                    "ENABLE_BANKING_REDIRECT_URL must have a non-empty path: " + url);
        }
    }
}
