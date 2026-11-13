package de.alexanderlindhorst.imapmcp.core.imap;

import jakarta.mail.MessagingException;

/**
 * An XOAUTH2 login was refused for good: the server does not offer {@code AUTH=XOAUTH2}, or it refused the renewed
 * token as well (SPEC 5.5). Deliberately not an {@link jakarta.mail.AuthenticationFailedException} and without a
 * cause, so that {@link LoginRefusal} does not count it towards {@code failed_logins} or {@code needs_relink}, and
 * so that no server text can travel with it. Maps to {@code AUTH_FAILED} {@code xoauth2_rejected}.
 */
public final class Xoauth2RejectedException extends MessagingException {

    private static final long serialVersionUID = 1L;

    public Xoauth2RejectedException() {
        super("xoauth2 rejected");
    }
}
