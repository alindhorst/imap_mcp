package de.alexanderlindhorst.imapmcp.core.imap;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import jakarta.mail.MessagingException;
import de.alexanderlindhorst.imapmcp.core.oauth.AccessToken;
import jakarta.mail.Session;
import java.time.Instant;
import java.util.Objects;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LoginProbe} on an {@link ImapSessionFactory}, which carries the SSRF-pinning socket factory in
 * {@code http-public}. The session is not cached; the Store is closed in every case. Nothing about the account or
 * the server's answer is logged.
 */
public final class ImapLoginProbe implements LoginProbe, Xoauth2LoginProbe {

    private static final Logger LOG = LoggerFactory.getLogger(ImapLoginProbe.class);

    private final ImapSessionFactory sessions;

    public ImapLoginProbe(ImapSessionFactory sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        if (!sessions.pinsSockets()) { // fail closed: a probe is an outgoing connection to a user-chosen host
            throw new IllegalArgumentException("the link probe needs a session factory with the pinned socket factory");
        }
    }

    @Override
    public Result probeXoauth2(MailAccount account, char[] accessToken) {
        Session session = sessions.xoauth2ProbeSession(account);
        // an AccessToken only to carry the value; the caller wipes the array, so nothing is closed here
        AccessToken token = new AccessToken(new Secret(accessToken), Instant.MAX);
        IMAPStore store = null;
        try {
            Xoauth2Connector.Connected c = Xoauth2Connector.connectOnce(session, account, token);
            store = c.store();
            ImapCapabilities.from(store); // the CAPABILITY step; the answer is not needed here
            return Result.OK;
        } catch (MessagingException | RuntimeException e) {
            ErrorCode code = ImapErrorMapper.map(e, "probe").error().code();
            LOG.debug("xoauth2 link probe failed (code={})", code);
            return code == ErrorCode.AUTH_FAILED ? Result.AUTH_FAILED : Result.UNREACHABLE;
        } finally {
            closeQuietly(store);
        }
    }

    @Override
    public Result probe(MailAccount account, char[] password) {
        Session session = sessions.probeSession(account);
        IMAPStore store = null;
        try {
            store = (IMAPStore) session.getStore(ImapSessionFactory.protocolFor(account));
            // Angus needs a String; it lives until garbage collected, like in the pool factory.
            store.connect(account.host(), account.port(), account.username(), new String(password));
            ImapCapabilities.from(store); // the CAPABILITY step; the answer is not needed here
            return Result.OK;
        } catch (MessagingException | RuntimeException e) {
            ErrorCode code = ImapErrorMapper.map(e, "probe").error().code();
            LOG.debug("link probe failed (code={})", code);
            return code == ErrorCode.AUTH_FAILED ? Result.AUTH_FAILED : Result.UNREACHABLE;
        } finally {
            closeQuietly(store);
        }
    }

    private static void closeQuietly(IMAPStore store) {
        if (store != null) {
            try {
                store.close(); // LOGOUT
            } catch (MessagingException | RuntimeException ignored) {
                // the probe has its answer already
            }
        }
    }
}
