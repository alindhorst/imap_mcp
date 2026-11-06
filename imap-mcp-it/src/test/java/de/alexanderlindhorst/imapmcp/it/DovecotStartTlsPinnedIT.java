package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
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
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * STARTTLS (port 143 style, {@code mail.imap.*} prefix, {@code starttls.required}) through the SSRF pinned socket
 * factory against Dovecot (SPEC 5.3, 5.8). GreenMail has no STARTTLS, so this is the only test of that path. The
 * host name {@code imap.example.test} is pinned to the container by the resolver seam and is a SAN of the fixture
 * certificate; hostname verification stays on.
 */
@Testcontainers
class DovecotStartTlsPinnedIT {

    private static final String NAME = "imap.example.test";
    private static final TenantId TENANT = new TenantId("t1");
    private static final String USER = "starttls-user";

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer();

    private final AtomicInteger lookups = new AtomicInteger();
    private final AtomicBoolean blocked = new AtomicBoolean();
    private ImapSessionFactory factory;

    @BeforeEach
    void setUp() {
        int port = DOVECOT.startTlsPort();
        PinnedSocketFactory sockets = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()), h -> {
            lookups.incrementAndGet();
            return new InetAddress[] {InetAddress.getByAddress(h, blocked.get() ? new byte[] {10, 0, 0, 5}
                    : InetAddress.getByName(DOVECOT.getHost()).getAddress())};
        }, a -> a.getAddress()[0] == 10, p -> p == port);
        factory = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            // the fixture certificate is self-signed; hostname verification (ssl.checkserveridentity) stays on
            p.put("mail.imap.ssl.trust", "*");
            return p;
        }, false, sockets);
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    private Session session() {
        MailAccount a = new MailAccount(new AccountId("a1"), "label", null, NAME, DOVECOT.startTlsPort(),
                Security.STARTTLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
        PoolKey key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, a, "acc_starttls"));
        return factory.sessionFor(key, a);
    }

    private Store connect(String host) throws MessagingException {
        Store store = session().getStore("imap");
        store.connect(host, DOVECOT.startTlsPort(), USER, DovecotContainer.PASSWORD);
        return store;
    }

    private static boolean chainContains(Throwable t, Class<?> type) {
        Deque<Throwable> todo = new ArrayDeque<>(List.of(t));
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
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
    void theSessionUsesTheImapPrefixWithRequiredStartTlsAndOurSocketFactory() {
        Properties p = session().getProperties();
        assertThat(p.getProperty("mail.imap.starttls.required")).isEqualTo("true");
        assertThat(p.getProperty("mail.imap.socketFactory.fallback")).isEqualTo("false");
        assertThat(p.get("mail.imap.socketFactory")).isInstanceOf(PinnedSocketFactory.class);
        assertThat(p.stringPropertyNames()).noneMatch(k -> k.startsWith("mail.imaps."));
    }

    @Test
    void starttlsLoginAndFolderListThroughThePinnedSocketWithHostnameVerification() throws Exception {
        try (Store store = connect(NAME)) {
            assertThat(store.isConnected()).isTrue();
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            assertThat(inbox.isOpen()).isTrue();
            inbox.close(false);
        }
        assertThat(lookups.get()).isPositive();
    }

    @Test
    void aCertificateForAnotherNameFailsTheStarttlsHandshake() {
        Throwable t = null;
        try (Store store = connect("other.example.test")) {
            store.isConnected();
        } catch (MessagingException e) {
            t = e;
        }
        assertThat(t).isNotNull();
        assertThat(chainContains(t, SSLException.class)).isTrue();
        assertThat(chainContains(t, AddressBlockedException.class)).isFalse();
    }

    @Test
    void aBlockedAddressIsRefusedBeforeAnyBytesAreSent() {
        blocked.set(true);
        Throwable t = null;
        try (Store store = connect(NAME)) {
            store.isConnected();
        } catch (MessagingException e) {
            t = e;
        }
        assertThat(t).isNotNull();
        var mapped = ImapErrorMapper.map(t, "r1");
        assertThat(mapped.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(mapped.error().details()).containsEntry("reason", "address_blocked");
    }

    @Test
    void theFolderConnectionIsCheckedToo() throws Exception {
        try (Store store = connect(NAME)) {
            store.getFolder("Other").create(Folder.HOLDS_MESSAGES);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            blocked.set(true);
            Throwable t = null;
            try {
                store.getFolder("Other").open(Folder.READ_ONLY);
            } catch (MessagingException e) {
                t = e;
            }
            assertThat(t).isNotNull();
            assertThat(ImapErrorMapper.map(t, "r1").error().details()).containsEntry("reason", "address_blocked");
            inbox.close(false);
        }
    }
}
