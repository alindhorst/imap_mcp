package de.alexanderlindhorst.imapmcp.core.imap;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.oauth.AccessToken;
import de.alexanderlindhorst.imapmcp.core.oauth.OAuthAccessTokens;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import java.time.Instant;
import java.util.Objects;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The XOAUTH2 connect path (SPEC 5.5): the access token from {@link OAuthAccessTokens} is the password of an
 * {@code AUTHENTICATE XOAUTH2}; the guard of {@link Xoauth2ImapStore} refuses a server that does not offer it. A
 * refusal of the token is answered with one {@code renewAfterRejection} and one more attempt; a second refusal is
 * {@link Xoauth2RejectedException}. A refusal text starting with {@code [UNAVAILABLE]} is never retried (D14). A
 * fresh Store is made for every attempt. Nothing about the token or the server's answer is logged.
 */
public final class Xoauth2Connector {

    private static final Logger LOG = LoggerFactory.getLogger(Xoauth2Connector.class);

    private Xoauth2Connector() {
    }

    /** A connected Store and the expiry of the token it authenticated with. */
    public record Connected(IMAPStore store, Instant tokenExpiresAt) {
    }

    /**
     * Connects an XOAUTH2 account. The tenant is the one of the pool key (never client-supplied); the account id is
     * the account's own.
     *
     * @throws Xoauth2RejectedException guard or second refusal
     * @throws jakarta.mail.AuthenticationFailedException {@code [UNAVAILABLE]} refusal (D14), not retried
     * @throws de.alexanderlindhorst.imapmcp.core.oauth.TokenAcquisitionException no token could be obtained
     */
    public static Connected connect(Session session, MailAccount account, TenantId tenant, OAuthAccessTokens tokens,
            Instant deadline) throws MessagingException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(tokens, "tokens");
        AccessToken first = tokens.current(tenant, account.id(), deadline);
        try {
            return attempt(session, account, first);
        } catch (MessagingException e) {
            if (isGuard(e)) {
                throw new Xoauth2RejectedException(); // nothing was sent: no point in a renewal
            }
            if (!LoginRefusal.isCredentialRefusal(e)) {
                throw e; // [UNAVAILABLE] (D14) and every non-refusal failure
            }
            LOG.debug("xoauth2 token refused, renewing once");
        }
        AccessToken renewed = tokens.renewAfterRejection(tenant, account.id(), first, deadline);
        try {
            return attempt(session, account, renewed);
        } catch (MessagingException e) {
            if (isGuard(e) || LoginRefusal.isCredentialRefusal(e)) {
                throw new Xoauth2RejectedException();
            }
            throw e;
        }
    }

    private static Connected attempt(Session session, MailAccount account, AccessToken token)
            throws MessagingException {
        IMAPStore store = ImapSessionFactory.newStore(session, account);
        try {
            // Angus needs a String; unlike the char[] it cannot be wiped and lives until garbage collected.
            store.connect(account.host(), account.port(), account.username(), new String(token.value().chars()));
            return new Connected(store, token.expiresAt());
        } catch (MessagingException | RuntimeException e) {
            try {
                store.close();
            } catch (MessagingException | RuntimeException ignored) {
                // the refusal is the result
            }
            throw e;
        }
    }

    private static boolean isGuard(Throwable t) {
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof Xoauth2ImapStore.NotAdvertised) {
                return true;
            }
        }
        return false;
    }
}
