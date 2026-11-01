package de.alexanderlindhorst.imapmcp.core.handle;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.Macs;
import de.alexanderlindhorst.imapmcp.core.crypto.UnknownKidException;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Signed message handles, format {@code m1.<base64url_nopad(payload || tag)>}. Payload is
 * {@code kid u8 | uidvalidity u32 | uid u32 | folder_len u16 | folder UTF-8}; tag is the first 16 bytes of
 * HMAC-SHA256 over {@code "imap-mcp:msg:v1" 0x00 lp(tenant) lp(account) payload}. Tenant and account are only in
 * the MAC input. Deterministic, no expiry.
 */
public final class MessageHandles {

    /** Outcome of {@link #decode}; every failure is the same {@link Invalid} value, no reason is exposed. */
    public sealed interface Decoded {
        record Ok(MessageRef ref) implements Decoded {
        }

        record Invalid() implements Decoded {
        }
    }

    static final int MAX_FOLDER_BYTES = 1000;
    static final int MAX_HANDLE_CHARS = 1400;
    private static final String PREFIX = "m1.";
    private static final byte[] CONTEXT = "imap-mcp:msg:v1".getBytes(StandardCharsets.UTF_8);
    private static final int TAG_LEN = 16;
    private static final int HEADER_LEN = 1 + 4 + 4 + 2;
    private static final Decoded INVALID = new Decoded.Invalid();

    private final KeyRing keys;

    public MessageHandles(KeyRing keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** @throws IllegalArgumentException if the folder name exceeds 1000 UTF-8 bytes (callers guard this earlier) */
    public String encode(TenantId tenant, AccountId account, MessageRef ref) {
        byte[] folder = ref.folder().getBytes(StandardCharsets.UTF_8);
        if (folder.length > MAX_FOLDER_BYTES) {
            throw new IllegalArgumentException("folder name exceeds " + MAX_FOLDER_BYTES + " UTF-8 bytes");
        }
        int kid = keys.activeKid();
        byte[] payload = ByteBuffer.allocate(HEADER_LEN + folder.length)
                .put((byte) kid)
                .putInt((int) ref.uidValidity())
                .putInt((int) ref.uid())
                .putShort((short) folder.length)
                .put(folder)
                .array();
        byte[] tag = tag(keys.subkey(kid, KeyRing.MSG_HANDLE), tenant, account, payload);
        byte[] all = Arrays.copyOf(payload, payload.length + TAG_LEN);
        System.arraycopy(tag, 0, all, payload.length, TAG_LEN);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    public Decoded decode(TenantId tenant, AccountId account, String handle) {
        try {
            return verify(tenant, account, handle);
        } catch (RuntimeException e) {
            return INVALID;
        }
    }

    private Decoded verify(TenantId tenant, AccountId account, String handle) {
        if (handle == null || handle.length() > MAX_HANDLE_CHARS || !handle.startsWith(PREFIX)) {
            return INVALID;
        }
        String body = handle.substring(PREFIX.length());
        if (body.indexOf('=') >= 0) {
            return INVALID;
        }
        byte[] raw = Base64.getUrlDecoder().decode(body);
        // the JDK decoder ignores non-zero trailing bits; only the canonical encoding is accepted
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(body)) {
            return INVALID;
        }
        if (raw.length < HEADER_LEN + TAG_LEN) {
            return INVALID;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int kid = buf.get() & 0xFF;
        long uidValidity = buf.getInt() & MessageRef.MAX_U32;
        long uid = buf.getInt() & MessageRef.MAX_U32;
        int folderLen = buf.getShort() & 0xFFFF;
        if (folderLen > MAX_FOLDER_BYTES || raw.length != HEADER_LEN + folderLen + TAG_LEN) {
            return INVALID;
        }
        byte[] subkey;
        try {
            subkey = keys.subkey(kid, KeyRing.MSG_HANDLE);
        } catch (UnknownKidException e) {
            return INVALID;
        }
        byte[] payload = Arrays.copyOfRange(raw, 0, raw.length - TAG_LEN);
        byte[] presented = Arrays.copyOfRange(raw, raw.length - TAG_LEN, raw.length);
        if (!Macs.constantTimeEquals(tag(subkey, tenant, account, payload), presented)) {
            return INVALID;
        }
        String folder;
        try {
            folder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, HEADER_LEN, folderLen)).toString();
        } catch (CharacterCodingException e) {
            return INVALID;
        }
        return new Decoded.Ok(new MessageRef(folder, uidValidity, uid));
    }

    private static byte[] tag(byte[] subkey, TenantId tenant, AccountId account, byte[] payload) {
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        in.writeBytes(CONTEXT);
        in.write(0);
        lp(in, tenant.value());
        lp(in, account.value());
        in.writeBytes(payload);
        return Arrays.copyOf(Macs.hmac(subkey, in.toByteArray()), TAG_LEN);
    }

    private static void lp(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
        out.writeBytes(b);
    }
}
