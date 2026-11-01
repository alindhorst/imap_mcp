package de.alexanderlindhorst.imapmcp.core.imap.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolExecutor;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ImapLeaseTest {

    private static final String USER = "leaseuser";
    private static final String PASSWORD = "leasepass";
    private static final TenantId TENANT = new TenantId("t1");

    private GreenMail greenMail;
    private ImapSessionFactory sessions;
    private StorePool pool;
    private StoreLeases leases;
    private PoolKey key;
    private MailAccount account;
    private volatile CredentialStore credentials;
    private final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    private Logger traceLogger;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser(USER + "@localhost", USER, PASSWORD);
        useAccountOnPort(greenMail.getImaps().getPort());
        credentials = (t, a) -> Optional.of(new Secret(PASSWORD.toCharArray()));
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        traceLogger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(trace);
        trace.start();
    }

    private void useAccountOnPort(int port) {
        account = new MailAccount(new AccountId("a"), "label", null, "127.0.0.1", port,
                Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
        key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, account, "acc_test"));
    }

    private StoreLeases leases(Duration acquireTimeout) {
        PoolSettings s = new PoolSettings(3, 8, 64, Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofMinutes(30), acquireTimeout, Duration.ofSeconds(30), Duration.ofSeconds(120));
        pool = new StorePool(sessions, (t, a) -> credentials.resolve(t, a), new CapabilityCache(),
                k -> k.equals(key) ? account : null, s);
        leases = new StoreLeases(pool, acquireTimeout);
        return leases;
    }

    @AfterEach
    void tearDown() {
        traceLogger.detachAppender(trace);
        if (pool != null) {
            pool.close();
        }
        sessions.close();
        greenMail.stop();
    }

    private ToolCallContext ctx() {
        return new ToolCallContext(new AccountContext(TENANT), "rid");
    }

    private List<String> lines() {
        return trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private long count(String regex) {
        return lines().stream().filter(l -> l.matches(regex)).count();
    }

    private static void await(BooleanSupplier c) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(25);
        }
    }

    @Test
    void successfulCallExaminesClosesWithoutExpungeAndReturnsStore() throws Exception {
        leases(Duration.ofSeconds(10));
        try (ImapLease lease = leases.lease(key, ctx())) {
            IMAPFolder f = lease.openReadOnly("INBOX");
            assertThat(f.isOpen()).isTrue();
            assertThat(f.getMode()).isEqualTo(jakarta.mail.Folder.READ_ONLY);
        }
        assertThat(count("[A-Z]+\\d+ EXAMINE .*")).isEqualTo(1);
        assertThat(lines()).noneMatch(l -> l.contains("EXPUNGE")).noneMatch(l -> l.matches("[A-Z]+\\d+ SELECT .*"));
        assertThat(pool.numIdle(key)).isEqualTo(1);
        assertThat(pool.numActive(key)).isZero();
        assertThat(pool.destroyedCount()).isZero();
    }

    @Test
    void withLeaseDiscardsOnRuntimeExceptionAndRethrowsUnchanged() throws Exception {
        leases(Duration.ofSeconds(10));
        IllegalStateException boom = new IllegalStateException("boom");
        assertThatThrownBy(() -> leases.withLease(key, ctx(), lease -> {
            lease.store();
            throw boom;
        })).isSameAs(boom);
        assertThat(pool.destroyedCount()).isEqualTo(1);
        assertThat(pool.numIdle(key)).isZero();
        assertThat(pool.numActive(key)).isZero();
    }

    @Test
    void withLeaseDiscardsOnErrorAndRethrowsUnchanged() throws Exception {
        leases(Duration.ofSeconds(10));
        AssertionError boom = new AssertionError("boom");
        assertThatThrownBy(() -> leases.withLease(key, ctx(), lease -> {
            throw boom;
        })).isSameAs(boom);
        assertThat(pool.destroyedCount()).isEqualTo(1);
        assertThat(pool.numIdle(key)).isZero();
    }

    @Test
    void withLeasePropagatesMessagingExceptionWithoutMarkingBroken() throws Exception {
        leases(Duration.ofSeconds(10));
        jakarta.mail.MessagingException ex = new jakarta.mail.MessagingException("tool decides");
        assertThatThrownBy(() -> leases.withLease(key, ctx(), lease -> {
            throw ex;
        })).isSameAs(ex);
        assertThat(pool.destroyedCount()).isZero();
        assertThat(pool.numIdle(key)).isEqualTo(1);
    }

    @Test
    void withLeaseReturnsResultAndStoreOnNormalReturn() throws Exception {
        leases(Duration.ofSeconds(10));
        String r = leases.withLease(key, ctx(), lease -> "done");
        assertThat(r).isEqualTo("done");
        assertThat(pool.destroyedCount()).isZero();
        assertThat(pool.numIdle(key)).isEqualTo(1);
    }

    @Test
    void exceptionInScopeInvalidatesStore() throws Exception {
        leases(Duration.ofSeconds(10));
        assertThatThrownBy(() -> {
            try (ImapLease lease = leases.lease(key, ctx())) {
                try {
                    lease.openReadOnly("NoSuchFolder");
                } catch (jakarta.mail.MessagingException e) {
                    lease.markBroken();
                    throw e;
                }
            }
        }).isInstanceOf(jakarta.mail.MessagingException.class);
        await(() -> pool.destroyedCount() == 1);
        assertThat(pool.numActive(key)).isZero();
        assertThat(pool.numIdle(key)).isZero();
    }

    @Test
    void failingFolderOpenMarksLeaseBrokenByItself() throws Exception {
        leases(Duration.ofSeconds(10));
        try (ImapLease lease = leases.lease(key, ctx())) {
            // opening an unknown folder is an exception seen by the lease: no extra markBroken in the caller
            assertThatThrownBy(() -> lease.openReadOnly("NoSuchFolder")).isInstanceOf(Exception.class);
        }
        await(() -> pool.destroyedCount() == 1);
        assertThat(pool.numActive(key)).isZero();
    }

    @Test
    void cancellationInvalidatesStoreOnceEvenAfterClose() throws Exception {
        leases(Duration.ofSeconds(10));
        ToolCallContext c = ctx();
        ImapLease lease = leases.lease(key, c);
        lease.openReadOnly("INBOX");
        c.cancel();
        lease.close();
        await(() -> pool.destroyedCount() == 1);
        Thread.sleep(100);
        assertThat(pool.destroyedCount()).isEqualTo(1);
        assertThat(pool.numActive(key)).isZero();
        assertThat(pool.numIdle(key)).isZero();
    }

    @Test
    void leaseOnAlreadyCancelledContextIsRejected() {
        leases(Duration.ofSeconds(10));
        ToolCallContext c = ctx();
        c.cancel();
        assertThatThrownBy(() -> leases.lease(key, c)).isInstanceOf(StoreUnavailableException.class);
        assertThat(pool.numActive(key)).isZero();
    }

    @Test
    void secondFolderClosesFirstAndLeaseUsesOneSocket() throws Exception {
        leases(Duration.ofSeconds(10));
        greenMail.getManagers().getImapHostManager().createMailbox(
                greenMail.getManagers().getUserManager().getUserByEmail(USER + "@localhost"), "Other");
        try (ImapLease lease = leases.lease(key, ctx())) {
            IMAPFolder first = lease.openReadOnly("INBOX");
            assertThatThrownBy(lease::store).isInstanceOf(IllegalStateException.class);
            IMAPFolder second = lease.openReadOnly("Other");
            assertThat(first.isOpen()).isFalse();
            assertThat(second.isOpen()).isTrue();
        }
        assertThat(count("\\* OK IMAP4rev1 Server GreenMail.*")).isEqualTo(1);
        assertThat(count("[A-Z]+\\d+ EXAMINE .*")).isEqualTo(2);
        assertThat(lines()).noneMatch(l -> l.contains("EXPUNGE"));
    }

    @Test
    void storeIsAccessibleWhenNoFolderIsOpen() throws Exception {
        leases(Duration.ofSeconds(10));
        try (ImapLease lease = leases.lease(key, ctx())) {
            assertThat(lease.store().isConnected()).isTrue();
            lease.openReadOnly("INBOX");
            lease.closeFolder();
            assertThat(lease.store()).isNotNull();
        }
        assertThat(pool.numIdle(key)).isEqualTo(1);
    }

    @Test
    void concurrentLeasesGetDistinctStores() throws Exception {
        leases(Duration.ofSeconds(10));
        try (ImapLease a = leases.lease(key, ctx()); ImapLease b = leases.lease(key, ctx())) {
            assertThat(a.store()).isNotSameAs(b.store());
            assertThat(pool.numActive(key)).isEqualTo(2);
        }
    }

    @Test
    void borrowExceedingAcquireTimeoutYieldsUpstreamUnavailable() {
        leases(Duration.ofMillis(300));
        credentials = (t, a) -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        };
        long start = System.nanoTime();
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> leases.lease(key, ctx()));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
        assertThat(thrown).isInstanceOf(StoreUnavailableException.class);
        assertThat(ImapErrorMapper.map(thrown, "rid").error().code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(pool.numActive(key)).isZero();
    }

    @Test
    void storeConnectedAfterAcquireTimeoutIsNotLeaked() throws Exception {
        leases(Duration.ofMillis(200));
        CountDownLatch release = new CountDownLatch(1);
        credentials = (t, a) -> {
            while (true) { // deliberately ignores interrupts: the connect "finishes late"
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    // keep waiting
                }
            }
            return Optional.of(new Secret(PASSWORD.toCharArray()));
        };
        assertThatThrownBy(() -> leases.lease(key, ctx())).isInstanceOf(StoreUnavailableException.class);
        release.countDown();
        await(() -> pool.createdCount() == 1 && pool.destroyedCount() == 1);
        assertThat(pool.numActive(key)).isZero();
        assertThat(pool.numIdle(key)).isZero();
    }

    /** AC7a: worker blocked in the TLS handshake (no greeting) is released by the deadline path. */
    @Test
    void deadlineReleasesBorrowBlockedInSocketReadOnVirtualThread() throws Exception {
        try (ServerSocket stub = new ServerSocket(0)) {
            useAccountOnPort(stub.getLocalPort());
            leases(Duration.ofSeconds(10));
            AtomicReference<Socket> accepted = new AtomicReference<>();
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    accepted.set(stub.accept());
                } catch (IOException ignored) {
                    // closed
                }
            });
            ToolExecutor executor = new ToolExecutor(Duration.ofMillis(500),
                    new LogIds(new KeyRing(1, Map.of(1, new byte[32]))));
            AtomicReference<Thread> worker = new AtomicReference<>();
            var handler = executor.wrap("t", (c, a) -> {
                worker.set(Thread.currentThread());
                try (ImapLease l = leases.lease(key, c)) {
                    return new ToolOutcome.Success(Map.of());
                }
            });
            long start = System.nanoTime();
            ToolOutcome out = handler.handle(ctx(), Map.of());
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
            assertThat(out).isInstanceOf(ToolOutcome.Failure.class);
            assertThat(((ToolOutcome.Failure) out).error().code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(worker.get().join(Duration.ofSeconds(1))).isTrue();
            acceptor.join(1000);
            // the client side of the TCP connection must have been closed, not merely abandoned
            Socket s = accepted.get();
            assertThat(s).isNotNull();
            s.setSoTimeout(1000);
            InputStream in = s.getInputStream();
            int r;
            try {
                r = in.read();
                while (r != -1) {
                    r = in.read(); // the TLS ClientHello may arrive first
                }
            } catch (IOException e) {
                r = -1;
            }
            assertThat(r).isEqualTo(-1);
            s.close();
            assertThat(pool.createdCount()).isZero();
            assertThat(pool.numActive(key)).isZero();
        }
    }

    /** AC7b: worker blocked in a read on an established connection (server silent after lease). */
    @Test
    void deadlineReleasesLeaseBlockedInReadOfOpenFolder() throws Exception {
        try (FreezableProxy proxy = new FreezableProxy(greenMail.getImaps().getPort())) {
            useAccountOnPort(proxy.port());
            leases(Duration.ofSeconds(10));
            ToolExecutor executor = new ToolExecutor(Duration.ofMillis(500),
                    new LogIds(new KeyRing(1, Map.of(1, new byte[32]))));
            AtomicReference<Thread> worker = new AtomicReference<>();
            var handler = executor.wrap("t", (c, a) -> {
                worker.set(Thread.currentThread());
                try (ImapLease l = leases.lease(key, c)) {
                    proxy.freeze();
                    l.openReadOnly("INBOX");
                    return new ToolOutcome.Success(Map.of());
                } catch (jakarta.mail.MessagingException e) {
                    throw new IllegalStateException(e);
                }
            });
            long start = System.nanoTime();
            ToolOutcome out = handler.handle(ctx(), Map.of());
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
            assertThat(((ToolOutcome.Failure) out).error().code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(worker.get().join(Duration.ofSeconds(1))).isTrue();
            await(() -> pool.destroyedCount() == 1);
            assertThat(pool.numIdle(key)).isZero();
            assertThat(pool.numActive(key)).isZero();
        }
    }

    /** Byte-forwarding TCP proxy (TLS stays end to end) that can stop delivering server bytes. */
    private static final class FreezableProxy implements AutoCloseable {
        private final ServerSocket server;
        private final int target;
        private volatile boolean frozen;

        FreezableProxy(int target) throws IOException {
            this.target = target;
            this.server = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() {
            return server.getLocalPort();
        }

        void freeze() {
            frozen = true;
        }

        private void acceptLoop() {
            try {
                while (true) {
                    Socket client = server.accept();
                    Socket upstream = new Socket("127.0.0.1", target);
                    Thread.ofVirtual().start(() -> pump(client, upstream, false));
                    Thread.ofVirtual().start(() -> pump(upstream, client, true));
                }
            } catch (IOException ignored) {
                // closed
            }
        }

        private void pump(Socket from, Socket to, boolean serverToClient) {
            byte[] buf = new byte[8192];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (serverToClient && frozen) {
                        continue; // swallow
                    }
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // closed
            } finally {
                try {
                    to.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
