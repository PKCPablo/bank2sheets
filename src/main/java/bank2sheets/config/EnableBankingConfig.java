package bank2sheets.config;

import bank2sheets.Bank2SheetsException;
import java.net.URI;
import java.util.Map;

/**
 * Runtime configuration for the Enable Banking adapter, read from environment
 * variables at runtime and never stored in the repository:
 * <ul>
 *   <li>{@code ENABLE_BANKING_APP_ID} - Enable Banking application id (JWT {@code kid})</li>
 *   <li>{@code ENABLE_BANKING_PRIVATE_KEY_B64} - base64-encoded PKCS#8 PEM private key
 *       ({@code -----BEGIN PRIVATE KEY-----}; convert PKCS#1 keys with
 *       {@code openssl pkcs8 -topk8 -nocrypt})</li>
 *   <li>{@code ENABLE_BANKING_REDIRECT_URL} - application redirect URL; two modes:
 *       {@code http} on {@code 127.0.0.1}/{@code localhost} with an explicit port and a
 *       non-empty path (sandbox loopback listener), or {@code https} with a non-blank
 *       host and a non-empty path (production, e.g. the GitHub Pages callback page,
 *       where the user pastes the callback URL into the CLI)</li>
 * </ul>
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

    /**
     * Two redirect modes are accepted:
     * <ul>
     *   <li>{@code http} loopback: bound by a local HTTP listener, so it must point at
     *       127.0.0.1/localhost with an explicit port (an omitted port would surface as a
     *       raw {@code IllegalArgumentException} from {@code InetSocketAddress}) and a
     *       non-empty path.</li>
     *   <li>{@code https}: served by an external page (e.g. the GitHub Pages callback);
     *       requires a non-blank host and a non-empty path; the user pastes the callback
     *       URL into the CLI.</li>
     * </ul>
     */
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
