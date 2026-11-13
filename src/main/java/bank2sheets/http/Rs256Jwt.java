package bank2sheets.http;

import bank2sheets.Bank2SheetsException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Minimal RS256 JWT signer shared by the Google Sheets and Enable Banking
 * clients (JDK-only: {@link KeyFactory} + {@link Signature} + raw base64url).
 *
 * <p>The private key is a PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}).
 * A PKCS#1 key ({@code BEGIN RSA PRIVATE KEY}) must be converted first:
 * {@code openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem}.
 */
public final class Rs256Jwt {

    private Rs256Jwt() {
    }

    /**
     * Encodes a compact JWT: {@code base64url(header).base64url(claims).base64url(signature)}
     * with {@code SHA256withRSA} and raw base64url (no {@code =} padding).
     */
    public static String encode(String headerJson, String claimsJson, String privateKeyPem) {
        String signingInput = base64Url(headerJson.getBytes(StandardCharsets.UTF_8))
                + "." + base64Url(claimsJson.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(sign(signingInput.getBytes(StandardCharsets.UTF_8), privateKeyPem));
    }

    /** Signs the JWT signing input with the RSA key (PKCS#8 PEM). */
    static byte[] sign(byte[] data, String privateKeyPem) {
        try {
            String der = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(der)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new Bank2SheetsException("Cannot sign RS256 JWT (invalid PKCS#8 private key?)", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
