package de.alexanderlindhorst.imapmcp.it;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** PKCE (RFC 7636) S256 material for the connector test client. */
final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private Pkce() {
    }

    /** 32 random bytes, base64url: 43 unreserved characters (RFC 7636 section 4.1). */
    static String newVerifier() {
        return random(32);
    }

    /** {@code BASE64URL(SHA256(ASCII(verifier)))} (RFC 7636 section 4.2). */
    static String challenge(String verifier) {
        try {
            return B64.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 192 bits, above the 128 the ticket asks for. */
    static String newState() {
        return random(24);
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return B64.encodeToString(b);
    }
}
