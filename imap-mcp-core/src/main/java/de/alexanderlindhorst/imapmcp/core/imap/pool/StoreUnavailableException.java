package de.alexanderlindhorst.imapmcp.core.imap.pool;

/**
 * No Store could be leased (connect/auth failure, acquire timeout, cancellation). The message is fixed; the
 * cause chain is what {@code ImapErrorMapper} classifies (a {@code ConnectException} cause maps to
 * UPSTREAM_UNAVAILABLE, an {@code InterruptedException} to TIMEOUT).
 */
public final class StoreUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StoreUnavailableException(Throwable cause) {
        super("IMAP store unavailable", cause);
    }
}
