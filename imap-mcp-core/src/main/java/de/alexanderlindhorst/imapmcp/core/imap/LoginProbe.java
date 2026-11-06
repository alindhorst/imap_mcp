package de.alexanderlindhorst.imapmcp.core.imap;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;

/**
 * The link-time probe of the linking surface (SPEC 5.3): connect, TLS, LOGIN, CAPABILITY, LOGOUT, no folder. It
 * runs through the same pinned socket factory as every account connection and never counts towards the failed-login
 * counter of an account.
 */
@FunctionalInterface
public interface LoginProbe {

    /** What the probe found; never server text. */
    enum Result {
        OK,
        /** The server refused the LOGIN. */
        AUTH_FAILED,
        /** Refused host or address, connect, TLS or timeout problems, or any other failure. */
        UNREACHABLE
    }

    /** The caller wipes {@code password}. */
    Result probe(MailAccount account, char[] password);
}
