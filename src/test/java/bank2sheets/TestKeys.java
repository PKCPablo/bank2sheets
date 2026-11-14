package bank2sheets;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;

/**
 * Throwaway RSA key pair used only in tests (never a real credential); the fakes
 * never verify signatures. Generated once per JVM and shared by every test that
 * needs a PEM-encoded private key.
 */
public final class TestKeys {

    private static final KeyPair KEY_PAIR = generateKeyPair();

    private TestKeys() {
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate test RSA key", e);
        }
    }

    public static KeyPair keyPair() {
        return KEY_PAIR;
    }

    public static PrivateKey privateKey() {
        return KEY_PAIR.getPrivate();
    }

    /** PKCS#8 PEM of the throwaway private key. */
    public static String pkcs8Pem() {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(KEY_PAIR.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    /** Base64 of {@link #pkcs8Pem()}, as stored in config. */
    public static String base64Pem() {
        return Base64.getEncoder().encodeToString(pkcs8Pem().getBytes(StandardCharsets.UTF_8));
    }
}
