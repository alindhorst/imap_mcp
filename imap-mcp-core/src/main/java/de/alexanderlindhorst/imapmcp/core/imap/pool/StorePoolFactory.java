package de.alexanderlindhorst.imapmcp.core.imap.pool;

import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapCapabilities;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import org.apache.commons.pool2.KeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, validates and destroys the pooled {@link IMAPStore}s (SPEC 5.5). The account for a key comes from a
 * tenant-scoped lookup supplied by the caller, never from client strings; the secret is resolved at connect time
 * and never cached here.
 */
public final class StorePoolFactory implements KeyedPooledObjectFactory<PoolKey, IMAPStore> {

    private static final Logger LOG = LoggerFactory.getLogger(StorePoolFactory.class);

    private final ImapSessionFactory sessions;
    private final CredentialStore credentials;
    private final CapabilityCache capabilities;
    private final Function<PoolKey, MailAccount> accounts;
    private final PoolSettings settings;
    private final MetricsRecorder metrics;

    public StorePoolFactory(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings settings) {
        this(sessions, credentials, capabilities, accounts, settings, MetricsRecorder.NOOP);
    }

    /** {@code metrics} receives the connect+login latency of every new Store (SPEC 5.6). */
    public StorePoolFactory(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings settings, MetricsRecorder metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public PooledObject<IMAPStore> makeObject(PoolKey key) throws MessagingException {
        MailAccount account = accounts.apply(key);
        if (account == null) {
            throw new MessagingException("no account for pool key");
        }
        Session session = sessions.sessionFor(key, account);
        IMAPStore store = (IMAPStore) session.getStore(ImapSessionFactory.protocolFor(account));
        try (Secret secret = credentials.resolve(key.tenantId(), key.accountId())
                .orElseThrow(() -> new MessagingException("no credential for account"))) {
            // Angus needs a String. It is created only here and not retained; unlike the char[] it cannot be
            // wiped and lives until garbage collected.
            store.connect(account.host(), account.port(), account.username(), new String(secret.chars()));
            capabilities.put(key, ImapCapabilities.from(store));
        } catch (MessagingException | RuntimeException e) {
            closeQuietly(store);
            throw e;
        }
        return new DefaultPooledObject<>(store);
    }

    @Override
    public void destroyObject(PoolKey key, PooledObject<IMAPStore> p) {
        IMAPStore store = p.getObject();
        // LOGOUT may block up to the read timeout; neither the evictor nor the caller may wait for it.
        Thread.startVirtualThread(() -> closeQuietly(store));
    }

    @Override
    public boolean validateObject(PoolKey key, PooledObject<IMAPStore> p) {
        if (age(p).compareTo(settings.maxLifetime()) > 0) {
            return false;
        }
        if (p.getIdleDuration().compareTo(settings.validateAfterIdle()) <= 0) {
            return true;
        }
        try {
            return p.getObject().isConnected(); // Angus sends NOOP
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void activateObject(PoolKey key, PooledObject<IMAPStore> p) {
        // nothing to do
    }

    @Override
    public void passivateObject(PoolKey key, PooledObject<IMAPStore> p) {
        // nothing to do
    }

    static Duration age(PooledObject<?> p) {
        return Duration.between(p.getCreateInstant(), java.time.Instant.now());
    }

    private static void closeQuietly(IMAPStore store) {
        try {
            store.close();
        } catch (MessagingException | RuntimeException e) {
            LOG.debug("closing store failed: {}", e.getClass().getSimpleName());
        }
    }
}
