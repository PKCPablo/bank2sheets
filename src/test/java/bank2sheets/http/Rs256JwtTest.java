package bank2sheets.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.Bank2SheetsException;
import bank2sheets.TestKeys;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Rs256Jwt tests with the shared throwaway RSA key pair; no real credentials. */
class Rs256JwtTest {

    @Test
    void encodeProducesVerifiableCompactJwt() throws Exception {
        KeyPair keys = TestKeys.keyPair();
        String pem = TestKeys.pkcs8Pem();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"app-1\"}";
        String claims = "{\"iss\":\"enablebanking.com\",\"iat\":1000,\"exp\":4600}";

        String jwt = Rs256Jwt.encode(header, claims, pem);

        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        assertEquals(header, new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        assertEquals(claims, new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));

        // Raw base64url: no '=' padding anywhere.
        assertFalse(jwt.contains("="));

        // Signature verifies with the public key over the signing input.
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update(signingInput);
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

    @Test
    void invalidKeyFailsFast() {
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String claims = "{\"iss\":\"x\"}";
        String badPem = "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----";
        try {
            Rs256Jwt.encode(header, claims, badPem);
            org.junit.jupiter.api.Assertions.fail("expected Bank2SheetsException");
        } catch (Bank2SheetsException expected) {
            assertTrue(expected.getMessage().contains("RS256"));
        }
    }

}
