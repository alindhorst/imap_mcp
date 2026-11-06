package de.alexanderlindhorst.imapmcp.core.imap.pool;

import jakarta.mail.MessagingException;

/**
 * The account needs a re-link (SPEC 5.3), seen where the connection would be made: the account was active when the
 * call resolved it and flipped before the borrow. No connection was attempted. The error mapper turns it into
 * {@code AUTH_FAILED} {@code relink_required}.
 */
public final class AccountNeedsRelinkException extends MessagingException {

    public AccountNeedsRelinkException() {
        super("account needs a re-link");
    }
}
