package de.alexanderlindhorst.imapmcp.core.imap;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import java.util.Objects;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LoginProbe} on an {@link ImapSessionFactory}, which carries the SSRF-pinning socket factory in
 * {@code http-public}. The session is not cached; the Store is closed in every case. Nothing about the account or
 * the server's answer is logged.
 */
public final class ImapLoginProbe implements LoginProbe {

    private static final Logger LOG = LoggerFactory.getLogger(ImapLoginProbe.class);

    private final ImapSessionFactory sessions;

    public ImapLoginProbe(ImapSessionFactory sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
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
            if (store != null) {
                try {
                    store.close(); // LOGOUT
                } catch (MessagingException | RuntimeException ignored) {
                    // the probe has its answer already
                }
            }
        }
    }
}
