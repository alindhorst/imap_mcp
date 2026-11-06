package de.alexanderlindhorst.imapmcp.core.audit;

/**
 * An audit row could not be written (SPEC 5.3). Before a mutation this fails the call with nothing changed
 * (fail-closed, D5). Carries no message and no cause: neither may hold row data.
 */
public final class AuditWriteException extends RuntimeException {

    public AuditWriteException() {
        super("audit write failed", null, false, false);
    }
}
