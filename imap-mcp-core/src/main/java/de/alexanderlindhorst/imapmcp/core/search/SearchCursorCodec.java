package de.alexanderlindhorst.imapmcp.core.search;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;

public final class SearchCursorCodec {

    public sealed interface Decoded permits Invalid, CriteriaMismatch, Ok {}

    public record Invalid() implements Decoded {
        public de.alexanderlindhorst.imapmcp.core.error.ToolError toError(String requestId) {
            throw new UnsupportedOperationException();
        }
    }

    public record CriteriaMismatch() implements Decoded {
        public de.alexanderlindhorst.imapmcp.core.error.ToolError toError(String requestId) {
            throw new UnsupportedOperationException();
        }
    }

    public record Ok(long uidValidity, long lastUid) implements Decoded {}

    public SearchCursorCodec(KeyRing keys) {}

    public String encode(TenantId tenant, AccountId account, String resolvedFolder, SearchCriteria criteria,
            long uidValidity, long lastUid) {
        throw new UnsupportedOperationException();
    }

    public Decoded decode(TenantId tenant, AccountId account, String resolvedFolder, SearchCriteria criteria,
            String cursor) {
        throw new UnsupportedOperationException();
    }
}
