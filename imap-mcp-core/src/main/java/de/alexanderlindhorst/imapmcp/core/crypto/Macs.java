package de.alexanderlindhorst.imapmcp.core.crypto;

import java.security.MessageDigest;

/** HMAC-SHA256 and the single constant-time comparison used for all MAC checks (handles, cursors). */
public final class Macs {

    private Macs() {}

    public static byte[] hmac(byte[] key, byte[] data) {
        return Hkdf.hmac(key, data);
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
