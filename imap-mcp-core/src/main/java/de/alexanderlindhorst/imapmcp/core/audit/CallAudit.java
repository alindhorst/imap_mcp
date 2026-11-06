package de.alexanderlindhorst.imapmcp.core.audit;

import java.util.List;
import java.util.function.Supplier;

/** Stub. */
public final class CallAudit {

    private static final CallAudit DISABLED = new CallAudit(AuditLog.NONE, () -> null);

    public CallAudit(AuditLog log, Supplier<CallInfo> call) {
    }

    public static CallAudit disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return false;
    }

    public void begin(List<ItemRef> items) throws AuditWriteException {
    }

    public void confirmation(String confirmation) {
    }

    public void complete(List<ItemResult> items, String result, String errorCode) {
    }

    public void completeAborted(boolean mutationStarted) {
    }
}
