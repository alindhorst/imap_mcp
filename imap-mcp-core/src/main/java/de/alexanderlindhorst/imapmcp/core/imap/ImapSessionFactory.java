package de.alexanderlindhorst.imapmcp.core.imap;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import jakarta.mail.Session;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.UnaryOperator;

/**
 * One Jakarta Mail {@link Session} per pool key (SPEC 5.5). Property keys always use the prefix of the protocol
 * actually used ({@code mail.imaps.*} is ignored for {@code imap} and vice versa). TLS is mandatory; no password is
 * ever put into the properties.
 */
public final class ImapSessionFactory implements AutoCloseable {

    private final ImapTimeouts timeouts;
    private final UnaryOperator<Properties> propertiesHook;
    private final Map<PoolKey, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor writeTimeoutExecutor;

    public ImapSessionFactory(ImapTimeouts timeouts) {
        this(timeouts, UnaryOperator.identity());
    }

    /** {@code propertiesHook} is a test seam (for example to trust test certificates); never fed from config. */
    public ImapSessionFactory(ImapTimeouts timeouts, UnaryOperator<Properties> propertiesHook) {
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

    public Session sessionFor(PoolKey key, MailAccount account) {
        Objects.requireNonNull(key, "key");
        String protocol = protocolFor(account);
        return sessions.computeIfAbsent(key, k -> Session.getInstance(properties(protocol), null));
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
        return propertiesHook.apply(props);
    }

    @Override
    public void close() {
        writeTimeoutExecutor.shutdownNow();
        sessions.clear();
    }
}
