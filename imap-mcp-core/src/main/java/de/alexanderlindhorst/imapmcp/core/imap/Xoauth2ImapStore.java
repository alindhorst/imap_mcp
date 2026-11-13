package de.alexanderlindhorst.imapmcp.core.imap;

import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;

/**
 * An {@link IMAPStore} that refuses to authenticate unless the server advertises {@code AUTH=XOAUTH2} (SPEC 5.5).
 * Angus would otherwise fall back to {@code LOGIN user <token>} and send the access token as a plain password. The
 * hook runs after STARTTLS and before any authentication command, so nothing credential-bearing is sent.
 */
final class Xoauth2ImapStore extends IMAPStore {

    /** Thrown from the hook; Angus wraps it, {@link Xoauth2Connector} recognises it in the cause chain. */
    static final class NotAdvertised extends ProtocolException {
        private static final long serialVersionUID = 1L;

        NotAdvertised() {
            super("AUTH=XOAUTH2 not advertised");
        }
    }

    /**
     * {@code protocol} is {@code imap}, {@code imaps} or {@code gimaps}; the store takes its property prefix from
     * the URL's protocol, exactly as {@code Session.getStore(protocol)} would set it up.
     */
    Xoauth2ImapStore(Session session, String protocol) {
        super(session, new URLName(protocol, null, -1, null, null, null), protocol, !protocol.equals("imap"));
    }

    @Override
    protected void preLogin(IMAPProtocol protocol) throws ProtocolException {
        super.preLogin(protocol);
        if (!protocol.hasCapability("AUTH=XOAUTH2")) {
            throw new NotAdvertised();
        }
    }
}
