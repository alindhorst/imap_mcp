package de.alexanderlindhorst.imapmcp.store.jdbc;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Random secrets (256 bits, base64url) and their SHA-256, for session ids and OIDC state values. */
final class WebTokens {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebTokens() {
    }

    static byte[] randomBytes() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return b;
    }

    static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether the value has the shape of a token we issued; anything else never reaches the database. */
    static boolean wellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
