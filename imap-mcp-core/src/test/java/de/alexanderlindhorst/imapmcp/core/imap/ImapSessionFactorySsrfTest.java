package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.net.AddressBlockedException;
import de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.net.InetAddress;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The pinned socket factory as installed by {@link ImapSessionFactory} (SPEC 5.3). */
class ImapSessionFactorySsrfTest {

    private static final TenantId TENANT = new TenantId("t1");
    private static final String NAME = "imap.example.test";

    private GreenMail greenMail;
    private final AtomicInteger lookups = new AtomicInteger();
    private final AtomicBoolean flip = new AtomicBoolean();
    private ImapSessionFactory factory;

    @BeforeEach
    void start() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("user@localhost", "user", "pw");
    }

    @AfterEach
    void stop() {
        if (factory != null) {
            factory.close();
        }
        greenMail.stop();
    }

    private ImapSessionFactory factory(boolean checkIdentity) {
        int port = greenMail.getImaps().getPort();
        PinnedSocketFactory sockets = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()), h -> {
            lookups.incrementAndGet();
            return new InetAddress[] {InetAddress.getByAddress(h, flip.get() ? new byte[] {10, 0, 0, 5}
                    : new byte[] {127, 0, 0, 1})};
        }, a -> a.getAddress()[0] == 10, p -> p == port);
        factory = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*"); // the fixture certificate is self-signed
            p.put("mail.imaps.ssl.checkserveridentity", Boolean.toString(checkIdentity));
            // a hook must not be able to undo the pinning
            p.put("mail.imaps.ssl.socketFactory", javax.net.ssl.SSLSocketFactory.getDefault());
            p.put("mail.imaps.socks.host", "proxy.example.test");
            p.put("mail.imaps.proxy.host", "proxy.example.test");
            return p;
        }, false, sockets);
        return factory;
    }

    private static MailAccount account(Security security, Flavor flavor, int port) {
        return new MailAccount(new AccountId("a1"), "label", null, NAME, port, security, flavor, "user",
                new SecretRef("env:X"));
    }

    private Session session(boolean checkIdentity) {
        MailAccount a = account(Security.IMPLICIT_TLS, Flavor.GENERIC, greenMail.getImaps().getPort());
        PoolKey key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, a, "acc_test"));
        return factory(checkIdentity).sessionFor(key, a);
    }

    @Test
    void installsOurFactoryWithFallbackOffAndNoBypassKeysForEveryProtocol() {
        for (Object[] row : new Object[][] {{Security.IMPLICIT_TLS, Flavor.GENERIC, "imaps"},
                {Security.IMPLICIT_TLS, Flavor.GMAIL, "gimaps"}, {Security.STARTTLS, Flavor.GENERIC, "imap"}}) {
            String protocol = (String) row[2];
            MailAccount a = account((Security) row[0], (Flavor) row[1], 993);
            PoolKey key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, a, "acc_" + protocol));
            ImapSessionFactory f = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
                for (String prefix : List.of("imap", "imaps", "gimaps")) {
                    p.put("mail." + prefix + ".ssl.socketFactory", "x");
                    p.put("mail." + prefix + ".ssl.socketFactory.class", "x");
                    p.put("mail." + prefix + ".socketFactory.class", "x");
                    p.put("mail." + prefix + ".socketFactory.port", "1");
                    p.put("mail." + prefix + ".proxy.host", "x");
                    p.put("mail." + prefix + ".socks.host", "x");
                }
                return p;
            }, false, new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of())));
            try {
                Properties p = f.sessionFor(key, a).getProperties();
                String prefix = "mail." + protocol + ".";
                assertThat(p.get(prefix + "socketFactory")).isInstanceOf(PinnedSocketFactory.class);
                assertThat(p.getProperty(prefix + "socketFactory.fallback")).isEqualTo("false");
                assertThat(p.stringPropertyNames().stream().filter(k -> k.startsWith("mail.imap")
                        || k.startsWith("mail.gimap")).toList()).allMatch(k -> k.startsWith(prefix));
                assertThat(p.keySet().stream().map(Object::toString)).noneMatch(k -> k.contains("ssl.socketFactory")
                        || k.contains("socketFactory.class") || k.contains("socketFactory.port")
                        || k.contains(".proxy.") || k.contains(".socks."));
                assertThat(p.getProperty(prefix + "ssl.checkserveridentity")).isEqualTo("true");
            } finally {
                f.close();
            }
        }
    }

    @Test
    void withoutASocketFactoryNothingChanges() {
        MailAccount a = account(Security.IMPLICIT_TLS, Flavor.GENERIC, 993);
        PoolKey key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, a, "acc_plain"));
        try (ImapSessionFactory f = new ImapSessionFactory(ImapTimeouts.DEFAULTS)) {
            Properties p = f.sessionFor(key, a).getProperties();
            assertThat(p.keySet().stream().map(Object::toString)).noneMatch(k -> k.contains("socketFactory"));
        }
    }

    @Test
    void storeConnectsThroughThePinnedSocketAndLogsIn() throws Exception {
        try (Store store = session(false).getStore("imaps")) {
            store.connect(NAME, greenMail.getImaps().getPort(), "user", "pw");
            assertThat(store.isConnected()).isTrue();
        }
        assertThat(lookups.get()).isEqualTo(1);
    }

    private static boolean chainContains(Throwable t, Class<?> type) {
        java.util.Deque<Throwable> todo = new java.util.ArrayDeque<>(List.of(t));
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (!todo.isEmpty()) {
            Throwable cur = todo.poll();
            if (!seen.add(cur)) {
                continue;
            }
            if (type.isInstance(cur)) {
                return true;
            }
            if (cur.getCause() != null) {
                todo.add(cur.getCause());
            }
            if (cur instanceof MessagingException me && me.getNextException() != null) {
                todo.add(me.getNextException());
            }
        }
        return false;
    }

    @Test
    void certificateForAnotherNameFailsHostnameVerification() throws Exception {
        try (Store store = session(true).getStore("imaps")) {
            Throwable t = null;
            try {
                store.connect(NAME, greenMail.getImaps().getPort(), "user", "pw");
            } catch (MessagingException e) {
                t = e;
            }
            assertThat(t).isNotNull();
            assertThat(chainContains(t, SSLException.class)).isTrue();
            assertThat(chainContains(t, AddressBlockedException.class)).isFalse();
        }
    }

    @Test
    void checkRunsOnFolderConnectionsToo() throws Exception {
        try (Store store = session(false).getStore("imaps")) {
            store.connect(NAME, greenMail.getImaps().getPort(), "user", "pw");
            store.getFolder("Other").create(Folder.HOLDS_MESSAGES);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY); // takes the connection of the store
            int before = lookups.get();
            flip.set(true); // the next TCP connection resolves to a blocked address
            Folder second = store.getFolder("Other");
            Throwable t = null;
            try {
                second.open(Folder.READ_ONLY); // needs a connection of its own
            } catch (MessagingException e) {
                t = e;
            }
            assertThat(lookups.get()).as("the second TCP connection was resolved and checked").isGreaterThan(before);
            assertThat(t).isNotNull();
            assertThat(chainContains(t, AddressBlockedException.class)).isTrue();
            inbox.close(false);
        }
    }

    @Test
    void blockedConnectIsAnUpstreamErrorWithReasonAddressBlocked() throws Exception {
        flip.set(true);
        try (Store store = session(false).getStore("imaps")) {
            Throwable t = null;
            try {
                store.connect(NAME, greenMail.getImaps().getPort(), "user", "pw");
            } catch (MessagingException e) {
                t = e;
            }
            assertThat(t).isNotNull();
            var mapped = ImapErrorMapper.map(t, "r1");
            assertThat(mapped.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(mapped.error().code().retryable()).isFalse();
            assertThat(mapped.error().details()).containsEntry("reason", "address_blocked");
            assertThat(mapped.discardConnection()).isTrue();
        }
    }

    @Test
    void sslFailureIsStillUpstreamUnavailable() {
        assertThat(ImapErrorMapper.map(new SSLException("x"), "r1").error().code())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }
}
