package de.alexanderlindhorst.imapmcp.core.imap.pool;

import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.LoginOutcomes;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapCapabilities;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
    private final LoginOutcomes outcomes;
    /** Account revision each live Store was made for; entries leave in {@link #destroyObject}. */
    private final Map<IMAPStore, Long> revisions = new ConcurrentHashMap<>();

    public StorePoolFactory(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings settings) {
        this(sessions, credentials, capabilities, accounts, settings, MetricsRecorder.NOOP);
    }

    /** {@code metrics} receives the connect+login latency of every new Store (SPEC 5.6). */
    public StorePoolFactory(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings settings, MetricsRecorder metrics) {
        this(sessions, credentials, capabilities, accounts, settings, metrics, LoginOutcomes.NONE);
    }

    /** {@code outcomes} gets every real LOGIN result with the revision it used (SPEC 5.3 "Re-link"). */
    public StorePoolFactory(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings settings, MetricsRecorder metrics,
            LoginOutcomes outcomes) {
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public PooledObject<IMAPStore> makeObject(PoolKey key) throws MessagingException {
        Instant start = metrics.now();
        try {
            return connect(key);
        } finally { // success and failure alike: a slow refused login is still connect latency
            metrics.operation(key.tenantId(), ImapLease.METRIC_TOOL, "connect",
                    Duration.between(start, metrics.now()));
        }
    }

    private PooledObject<IMAPStore> connect(PoolKey key) throws MessagingException {
        MailAccount account = accounts.apply(key);
        if (account == null) {
            throw new MessagingException("no account for pool key");
        }
        if (account.status() != MailAccount.Status.ACTIVE) { // no connect for an account that needs a re-link
            throw new AccountNeedsRelinkException();
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
            if (isCountedRefusal(e)) {
                report(() -> outcomes.refused(key.tenantId(), key.accountId(), account.revision()));
            }
            throw e;
        }
        report(() -> outcomes.succeeded(key.tenantId(), key.accountId(), account.revision()));
        revisions.put(store, account.revision());
        return new DefaultPooledObject<>(store);
    }

    /**
     * A LOGIN answered {@code NO} that counts toward a re-link (SPEC 5.3): Angus reports it as an
     * {@link AuthenticationFailedException}; one whose server text carries {@code [UNAVAILABLE]} is a temporary
     * server problem, not a credential refusal. The text is only inspected, never logged.
     */
    static boolean isCountedRefusal(Throwable t) {
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof AuthenticationFailedException) {
                String text = t.getMessage();
                // the response code is the first thing in the server's text (RFC 5530)
                return text == null || !text.strip().toUpperCase(java.util.Locale.ROOT).startsWith("[UNAVAILABLE]");
            }
        }
        return false;
    }

    /** The counter update must never change the connect result: a failure is a WARN with the class only. */
    private void report(Runnable outcome) {
        try {
            outcome.run();
        } catch (RuntimeException e) {
            LOG.warn("login outcome not recorded ({})", e.getClass().getSimpleName());
        }
    }

    /** Revision of the account the Store was made for, or -1 for a Store this factory did not make. */
    long revisionOf(IMAPStore store) {
        return revisions.getOrDefault(store, -1L);
    }

    @Override
    public void destroyObject(PoolKey key, PooledObject<IMAPStore> p) {
        IMAPStore store = p.getObject();
        revisions.remove(store);
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
