package de.alexanderlindhorst.imapmcp.core.imap;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import jakarta.mail.Session;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import javax.net.SocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Jakarta Mail {@link Session} per pool key (SPEC 5.5). Property keys always use the prefix of the protocol
 * actually used ({@code mail.imaps.*} is ignored for {@code imap} and vice versa). TLS is mandatory; no password is
 * ever put into the properties.
 */
public final class ImapSessionFactory implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ImapSessionFactory.class);

    private final ImapTimeouts timeouts;
    private final boolean trace;
    private final SocketFactory socketFactory;
    private final AtomicBoolean traceWarned = new AtomicBoolean();
    private final UnaryOperator<Properties> propertiesHook;
    private final Map<SessionKey, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor writeTimeoutExecutor;

    public ImapSessionFactory(ImapTimeouts timeouts) {
        this(timeouts, UnaryOperator.identity(), false);
    }

    /** {@code trace} enables the opt-in IMAP protocol trace (config {@code imap.trace}, SPEC 5.6). */
    public ImapSessionFactory(ImapTimeouts timeouts, boolean trace) {
        this(timeouts, UnaryOperator.identity(), trace);
    }

    public ImapSessionFactory(ImapTimeouts timeouts, UnaryOperator<Properties> propertiesHook) {
        this(timeouts, propertiesHook, false);
    }

    /** {@code propertiesHook} is a test seam (for example to trust test certificates); never fed from config. */
    public ImapSessionFactory(ImapTimeouts timeouts, UnaryOperator<Properties> propertiesHook, boolean trace) {
        this(timeouts, propertiesHook, trace, null);
    }

    /**
     * {@code socketFactory} (nullable) is the SSRF-pinning socket factory of {@code http-public} (SPEC 5.3); when set
     * it is installed on every session.
     */
    public ImapSessionFactory(ImapTimeouts timeouts, UnaryOperator<Properties> propertiesHook, boolean trace,
            SocketFactory socketFactory) {
        this.trace = trace;
        this.socketFactory = socketFactory;
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.propertiesHook = Objects.requireNonNull(propertiesHook, "propertiesHook");
        this.writeTimeoutExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "imap-write-timeout");
            t.setDaemon(true);
            return t;
        });
        this.writeTimeoutExecutor.setRemoveOnCancelPolicy(true);
    }

    /** Protocol for {@code Session.getStore(protocol)}; also decides the property prefix. */
    public static String protocolFor(MailAccount account) {
        if (account.flavor() == Flavor.GMAIL) {
            if (account.security() != Security.IMPLICIT_TLS) {
                throw new IllegalArgumentException("Gmail accounts require implicit TLS");
            }
            return "gimaps";
        }
        return account.security() == Security.IMPLICIT_TLS ? "imaps" : "imap";
    }

    /** Sessions are keyed by pool key and revision: a revision may change protocol and so the property prefix. */
    private record SessionKey(PoolKey key, long revision) {
    }

    public Session sessionFor(PoolKey key, MailAccount account) {
        Objects.requireNonNull(key, "key");
        String protocol = protocolFor(account);
        return sessions.computeIfAbsent(new SessionKey(key, key.revision()), k -> newSession(protocol));
    }

    /** A session for one probe: same properties (and pinned socket factory) as the pool's, but not cached. */
    public Session probeSession(MailAccount account) {
        return newSession(protocolFor(account));
    }

    private Session newSession(String protocol) {
        Session session = Session.getInstance(properties(protocol), null);
        // Always replace the debug sink so debug output can never reach System.out (stdio protocol channel).
        session.setDebugOut(new RedactingDebugStream());
        session.setDebug(trace);
        if (trace && traceWarned.compareAndSet(false, true)) {
            LOG.warn("IMAP protocol trace enabled: the log may contain message content "
                    + "(subjects, addresses, bodies). Disable after diagnosis.");
        }
        return session;
    }

    private Properties properties(String protocol) {
        String p = "mail." + protocol + ".";
        Properties props = new Properties();
        props.setProperty(p + "connectiontimeout", Long.toString(timeouts.connect().toMillis()));
        props.setProperty(p + "timeout", Long.toString(timeouts.read().toMillis()));
        props.setProperty(p + "writetimeout", Long.toString(timeouts.write().toMillis()));
        // put, not setProperty: the value is an object. Without it Angus creates a thread per socket.
        props.put(p + "executor.writetimeout", writeTimeoutExecutor);
        props.setProperty(p + "connectionpoolsize", "1");
        props.setProperty(p + "ssl.checkserveridentity", "true");
        props.setProperty(p + "peek", "true");
        props.setProperty(p + "throwsearchexception", "true");
        props.setProperty(p + "sasl.enable", "false");
        props.setProperty(p + "auth.mechanisms", "LOGIN PLAIN");
        if (protocol.equals("imap")) {
            props.setProperty(p + "starttls.enable", "true");
            props.setProperty(p + "starttls.required", "true");
        }
        props.setProperty("mail.debug.auth", "false");
        props.setProperty("mail.debug.auth.username", "false");
        props.setProperty("mail.debug.auth.password", "false");
        Properties out = propertiesHook.apply(props);
        if (socketFactory != null) {
            pin(out, p);
        }
        return out;
    }

    /**
     * Installs the pinning socket factory (SPEC 5.3). Angus' default fallback would retry with a plain socket and
     * re-resolve the name, so it is off; keys that would route around the factory are removed.
     */
    private void pin(Properties props, String p) {
        // keySet, not stringPropertyNames: a factory is stored as an object and would be skipped
        for (Object key : List.copyOf(props.keySet())) {
            if (key instanceof String k && (k.startsWith(p + "ssl.socketFactory") || k.startsWith(p + "socketFactory")
                    || k.startsWith(p + "proxy.") || k.startsWith(p + "socks."))) {
                props.remove(key);
            }
        }
        props.put(p + "socketFactory", socketFactory); // an object, not a class name
        props.setProperty(p + "socketFactory.fallback", "false");
    }

    @Override
    public void close() {
        writeTimeoutExecutor.shutdownNow();
        sessions.clear();
    }
}
