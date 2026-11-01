package de.alexanderlindhorst.imapmcp.core.search;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.Macs;
import de.alexanderlindhorst.imapmcp.core.crypto.UnknownKidException;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Signed paging cursor: {@code "c1." + base64url_nopad(kid u8 | uidvalidity u32 | last_uid u32 | qhash 8 | tag 16)}.
 * The tag is the first 16 bytes of HMAC-SHA256 under the "cursor" subkey over a domain separator, the
 * length-prefixed tenant, account and resolved folder full name, and the payload without the tag. Tenant,
 * account and folder are therefore bound by the MAC without being part of the wire format.
 */
public final class SearchCursorCodec {

    /** Outcome of {@link #decode}. The UIDVALIDITY check is up to the caller after EXAMINE. */
    public sealed interface Decoded permits Invalid, CriteriaMismatch, Ok {}

    /** Format, kid, MAC, tenant, account, folder or size failure; deliberately not distinguishable. */
    public record Invalid() implements Decoded {
        public ToolError toError(String requestId) {
            return ToolError.invalidCursor(requestId);
        }
    }

    /** MAC valid but the query hash differs from the current request. */
    public record CriteriaMismatch() implements Decoded {
        public ToolError toError(String requestId) {
            return ToolError.invalidCursorCriteriaMismatch(requestId);
        }
    }

    /** Both values are unsigned 32-bit, held as long. */
    public record Ok(long uidValidity, long lastUid) implements Decoded {}

    public static final int MAX_INPUT_CHARS = 256;

    private static final String PREFIX = "c1.";
    private static final byte[] DOMAIN = "imap-mcp:cursor:v1".getBytes(StandardCharsets.UTF_8);
    private static final int PAYLOAD_LEN = 1 + 4 + 4 + QueryHash.LENGTH;
    private static final int TAG_LEN = 16;
    private static final long U32_MAX = 0xFFFFFFFFL;

    private final KeyRing keys;

    public SearchCursorCodec(KeyRing keys) {
        this.keys = keys;
    }

    public String encode(TenantId tenant, AccountId account, String resolvedFolder, SearchCriteria criteria,
            long uidValidity, long lastUid) {
        if (uidValidity < 0 || uidValidity > U32_MAX || lastUid < 0 || lastUid > U32_MAX) {
            throw new IllegalArgumentException("uid values must be unsigned 32-bit");
        }
        int kid = keys.activeKid();
        byte[] payload = ByteBuffer.allocate(PAYLOAD_LEN).put((byte) kid).putInt((int) uidValidity)
                .putInt((int) lastUid).put(QueryHash.of(criteria, resolvedFolder)).array();
        byte[] tag = tag(keys.subkey(kid, KeyRing.CURSOR), tenant, account, resolvedFolder, payload);
        byte[] all = Arrays.copyOf(payload, PAYLOAD_LEN + TAG_LEN);
        System.arraycopy(tag, 0, all, PAYLOAD_LEN, TAG_LEN);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    public Decoded decode(TenantId tenant, AccountId account, String resolvedFolder, SearchCriteria criteria,
            String cursor) {
        byte[] raw = parse(cursor);
        if (raw == null) {
            return new Invalid();
        }
        byte[] payload = Arrays.copyOf(raw, PAYLOAD_LEN);
        byte[] tag = Arrays.copyOfRange(raw, PAYLOAD_LEN, raw.length);
        byte[] expected;
        try {
            expected = tag(keys.subkey(raw[0] & 0xFF, KeyRing.CURSOR), tenant, account, resolvedFolder, payload);
        } catch (UnknownKidException e) {
            return new Invalid();
        }
        if (!Macs.constantTimeEquals(expected, tag)) {
            return new Invalid();
        }
        byte[] qhash = Arrays.copyOfRange(payload, 9, PAYLOAD_LEN);
        if (!Macs.constantTimeEquals(qhash, QueryHash.of(criteria, resolvedFolder))) {
            return new CriteriaMismatch();
        }
        ByteBuffer bb = ByteBuffer.wrap(payload, 1, 8);
        return new Ok(bb.getInt() & U32_MAX, bb.getInt() & U32_MAX);
    }

    /** Returns the raw bytes (payload and tag) or null for anything that is not a canonical c1 cursor. */
    private static byte[] parse(String cursor) {
        if (cursor == null || cursor.length() > MAX_INPUT_CHARS || !cursor.startsWith(PREFIX)) {
            return null;
        }
        String body = cursor.substring(PREFIX.length());
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (raw.length != PAYLOAD_LEN + TAG_LEN) {
            return null;
        }
        // rejects padding and non-zero trailing bits: only the unique canonical encoding passes
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(body)) {
            return null;
        }
        return raw;
    }

    private static byte[] tag(byte[] key, TenantId tenant, AccountId account, String folder, byte[] payload) {
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        in.writeBytes(DOMAIN);
        lp(in, tenant.value());
        lp(in, account.value());
        lp(in, folder);
        in.writeBytes(payload);
        return Arrays.copyOf(Macs.hmac(key, in.toByteArray()), TAG_LEN);
    }

    /** u32 big-endian length, then the UTF-8 bytes. */
    private static void lp(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
        out.writeBytes(b);
    }
}
