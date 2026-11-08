package de.alexanderlindhorst.imapmcp.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Log-safe identifiers for MDC: HMAC-SHA256 with the "log" subkey, 12 lowercase hex chars, domain-separated. */
public final class LogIds {

    private static final int HEX_CHARS = 12;

    private final byte[] logKey;

    public LogIds(KeyRing keyRing) {
        this.logKey = keyRing.activeSubkey(KeyRing.LOG);
    }

    public String tenant(String tenantId) {
        return hash("tenant:", tenantId);
    }

    public String account(String accountId) {
        return hash("account:", accountId);
    }

    /**
     * The hashed principal of SPEC 5.3 (D11, v0.24), for the WARN about a principal without a tenant row. The
     * label {@code principal:} separates it from the tenant and account ids; issuer and subject are length-prefixed
     * (4 bytes, big-endian, UTF-8), so no two pairs share an encoding.
     */
    public String principal(String issuer, String subject) {
        byte[] label = "principal:".getBytes(StandardCharsets.US_ASCII);
        byte[] iss = issuer.getBytes(StandardCharsets.UTF_8);
        byte[] sub = subject.getBytes(StandardCharsets.UTF_8);
        byte[] data = java.nio.ByteBuffer.allocate(label.length + 8 + iss.length + sub.length)
                .put(label).putInt(iss.length).put(iss).putInt(sub.length).put(sub).array();
        return HexFormat.of().formatHex(Macs.hmac(logKey, data)).substring(0, HEX_CHARS);
    }

    private String hash(String domain, String id) {
        byte[] mac = Macs.hmac(logKey, (domain + id).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac).substring(0, HEX_CHARS);
    }
}
