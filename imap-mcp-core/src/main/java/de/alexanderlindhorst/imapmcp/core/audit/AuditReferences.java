package de.alexanderlindhorst.imapmcp.core.audit;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.Macs;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Reference hashes of audit rows (SPEC 5.3): HMAC-SHA256 under the {@code audit} HKDF subkey of a ring key over
 * a fixed label and the length-prefixed (4-byte big-endian) UTF-8 tenant, account and folder full name followed
 * by UIDVALIDITY and UID as 8-byte big-endian values, truncated to 16 bytes, plus the key id. An operator who
 * holds the ring can match a known handle to a row; nobody can read folder names or UIDs from the table.
 */
public final class AuditReferences {

    public static final int HASH_BYTES = 16;
    private static final byte[] LABEL = "imap-mcp:audit:v1".getBytes(StandardCharsets.UTF_8);

    /** The truncated HMAC and the key id it was made under. */
    public record Ref(byte[] hash, int keyId) {
    }

    private final KeyRing keys;

    public AuditReferences(KeyRing keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** Under the signing key. */
    public Ref ref(TenantId tenant, AccountId account, ItemRef item) {
        return refUnder(keys.activeKid(), tenant, account, item);
    }

    /** Under a specific ring key, to match an operator-supplied handle to old rows; {@code UnknownKidException} if removed. */
    public Ref refUnder(int keyId, TenantId tenant, AccountId account, ItemRef item) {
        byte[] subkey = keys.subkey(keyId, KeyRing.AUDIT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, LABEL);
        field(out, tenant.value().getBytes(StandardCharsets.UTF_8));
        field(out, account.value().getBytes(StandardCharsets.UTF_8));
        field(out, item.folder().getBytes(StandardCharsets.UTF_8));
        out.writeBytes(ByteBuffer.allocate(16).putLong(item.uidValidity()).putLong(item.uid()).array());
        return new Ref(Arrays.copyOf(Macs.hmac(subkey, out.toByteArray()), HASH_BYTES), keyId);
    }

    private static void field(ByteArrayOutputStream out, byte[] value) {
        out.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());
        out.writeBytes(value);
    }
}
