package de.alexanderlindhorst.imapmcp.core.audit;

import java.util.List;

/**
 * The multi-tenant audit log (SPEC 5.3). Writes are fail-closed (D5): {@link #begin} and {@link #event} throw
 * {@link AuditWriteException} and the caller changes nothing. {@link #NONE} is the single-tenant modes' log, which
 * keep the {@code imapmcp.audit} log line instead.
 */
public interface AuditLog {

    AuditLog NONE = new AuditLog() {
        @Override
        public Entry begin(CallInfo call, List<ItemRef> items) {
            return completion -> { };
        }

        @Override
        public void event(EventInfo event) {
        }
    };

    /** Records the call before its first mutation; one row per item, or one row when there are no items. */
    Entry begin(CallInfo call, List<ItemRef> items) throws AuditWriteException;

    /** Records a linking event. */
    void event(EventInfo event) throws AuditWriteException;

    /** The rows of one begun call. */
    interface Entry {
        /** Completes the rows; a failure here never undoes the mutation (callers log it and return the result). */
        void complete(Completion completion) throws AuditWriteException;
    }
}
