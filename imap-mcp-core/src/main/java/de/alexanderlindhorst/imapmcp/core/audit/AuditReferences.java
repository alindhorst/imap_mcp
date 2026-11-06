package de.alexanderlindhorst.imapmcp.core.audit;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;

/** Reference hashes of audit rows (SPEC 5.3). */
public final class AuditReferences {

    public static final int HASH_BYTES = 16;

    /** The truncated HMAC and the key id it was made under. */
    public record Ref(byte[] hash, int keyId) {
    }

    public AuditReferences(KeyRing keys) {
    }

    /** Under the signing key. */
    public Ref ref(TenantId tenant, AccountId account, ItemRef item) {
        throw new UnsupportedOperationException();
    }

    /** Under a specific ring key, to match an operator-supplied handle to old rows. */
    public Ref refUnder(int keyId, TenantId tenant, AccountId account, ItemRef item) {
        throw new UnsupportedOperationException();
    }
}
