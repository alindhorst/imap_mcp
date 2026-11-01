package de.alexanderlindhorst.imapmcp.core.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HKDF-SHA256 (RFC 5869) on the JDK's HmacSHA256. */
final class Hkdf {

    static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    static byte[] extract(byte[] salt, byte[] ikm) {
        byte[] s = salt.length == 0 ? new byte[HASH_LEN] : salt;
        return hmac(s, ikm);
    }

    static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length < 0 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("invalid HKDF output length");
        }
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < length; i++) {
            Mac mac = mac(prk);
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(HASH_LEN, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    static byte[] hmac(byte[] key, byte[] data) {
        return mac(key).doFinal(data);
    }

    private static Mac mac(byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
