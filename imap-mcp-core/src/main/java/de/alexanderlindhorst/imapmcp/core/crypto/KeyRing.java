package de.alexanderlindhorst.imapmcp.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Master keys by key id (u8). One kid signs, all configured kids verify. Purpose subkeys are derived
 * with HKDF-SHA256. Key bytes never leave via toString() or exceptions.
 */
public final class KeyRing {

    public static final String MSG_HANDLE = "msg-handle";
    public static final String CURSOR = "cursor";
    public static final String LOG = "log";
    /** Audit reference hashes of the multi-tenant audit log (SPEC 5.3, 5.4). */
    public static final String AUDIT = "audit";
    /** Reserved, unused in v1. */
    public static final String PART_REF = "part-ref";

    private static final int KEY_LEN = 32;

    private final int activeKid;
    private final Map<Integer, byte[]> masters;

    public KeyRing(int activeKid, Map<Integer, byte[]> masterKeys) {
        Map<Integer, byte[]> copy = new HashMap<>();
        masterKeys.forEach((kid, key) -> {
            if (kid < 0 || kid > 255) {
                throw new IllegalArgumentException("kid out of range 0..255");
            }
            if (key == null || key.length != KEY_LEN) {
                throw new IllegalArgumentException("master key must be " + KEY_LEN + " bytes");
            }
            copy.put(kid, key.clone());
        });
        if (!copy.containsKey(activeKid)) {
            throw new IllegalArgumentException("active kid is not in the key ring");
        }
        this.activeKid = activeKid;
        this.masters = copy;
    }

    public int activeKid() {
        return activeKid;
    }

    /** Subkey for verification or signing under the given kid; throws {@link UnknownKidException}. */
    public byte[] subkey(int kid, String purpose) {
        byte[] master = masters.get(kid);
        if (master == null) {
            throw new UnknownKidException(kid);
        }
        byte[] prk = Hkdf.extract(new byte[0], master);
        return Hkdf.expand(prk, purpose.getBytes(StandardCharsets.UTF_8), KEY_LEN);
    }

    public byte[] activeSubkey(String purpose) {
        return subkey(activeKid, purpose);
    }

    @Override
    public String toString() {
        return "KeyRing[activeKid=" + activeKid + ", kids=" + masters.size() + "]";
    }
}
