package de.alexanderlindhorst.imapmcp.core.error;

/** Admission refused the call (capacity exhausted); maps to SERVER_BUSY and never discards a connection. */
public final class ServerBusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServerBusyException() {
        super("server busy", null, false, false);
    }
}
