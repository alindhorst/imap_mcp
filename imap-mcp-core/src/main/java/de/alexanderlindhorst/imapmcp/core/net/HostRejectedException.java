package de.alexanderlindhorst.imapmcp.core.net;

/** A host, port or security combination the SSRF policy does not accept. The message never carries the host. */
public final class HostRejectedException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public enum Reason { INVALID_HOST, IP_LITERAL, NOT_ALLOWED, PORT }

    private final Reason reason;

    public HostRejectedException(Reason reason) {
        super(reason.name().toLowerCase(java.util.Locale.ROOT));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
