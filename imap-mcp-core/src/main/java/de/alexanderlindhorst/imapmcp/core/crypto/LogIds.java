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

    /** Stub (red commit): the hashed principal of SPEC 5.3 (D11, v0.24). */
    public String principal(String issuer, String subject) {
        return "";
    }

    private String hash(String domain, String id) {
        byte[] mac = Macs.hmac(logKey, (domain + id).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac).substring(0, HEX_CHARS);
    }
}
