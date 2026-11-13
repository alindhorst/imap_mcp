package de.alexanderlindhorst.imapmcp.core.imap;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.oauth.OAuthAccessTokens;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import java.time.Instant;
import org.eclipse.angus.mail.imap.IMAPStore;

/** The XOAUTH2 connect path (SPEC 5.5). */
public final class Xoauth2Connector {

    private Xoauth2Connector() {
    }

    /** A connected Store and the expiry of the token it authenticated with. */
    public record Connected(IMAPStore store, Instant tokenExpiresAt) {
    }

    public static Connected connect(Session session, MailAccount account, TenantId tenant, OAuthAccessTokens tokens,
            Instant deadline) throws MessagingException {
        throw new UnsupportedOperationException("not yet");
    }
}
