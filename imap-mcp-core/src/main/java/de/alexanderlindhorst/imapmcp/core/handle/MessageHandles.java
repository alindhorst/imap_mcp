package de.alexanderlindhorst.imapmcp.core.handle;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;

/** Signed message handles, format {@code m1.<base64url(payload || tag)>}. */
public final class MessageHandles {

    /** Outcome of {@link #decode}; every failure is the same {@link Invalid} value, no reason is exposed. */
    public sealed interface Decoded {
        record Ok(MessageRef ref) implements Decoded {
        }

        record Invalid() implements Decoded {
        }
    }

    public MessageHandles(KeyRing keys) {
    }

    public String encode(TenantId tenant, AccountId account, MessageRef ref) {
        throw new UnsupportedOperationException();
    }

    public Decoded decode(TenantId tenant, AccountId account, String handle) {
        throw new UnsupportedOperationException();
    }
}
