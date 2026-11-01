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

class AdmittedLeaseTest {

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

    private Admission admission;

    private StoreLeases leases(Duration acquireTimeout) {
        PoolSettings s = new PoolSettings(1, 8, 64, Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofMinutes(30), acquireTimeout, Duration.ofSeconds(30), Duration.ofSeconds(120));
        pool = new StorePool(sessions, (t, a) -> credentials.resolve(t, a), new CapabilityCache(),
                k -> k.equals(key) ? account : null, s);
        admission = new Admission(s);
        leases = new StoreLeases(pool, acquireTimeout, admission);
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

    private void assertAllPermitsFree() {
        assertThat(admission.availableGlobal()).isEqualTo(64);
        assertThat(admission.availableTenant(TENANT)).isEqualTo(8);
        assertThat(admission.availableMailbox(key)).isEqualTo(1);
    }

    @Test
    void secondCallToSameMailboxIsRefusedFastWhileFirstHoldsLease() throws Exception {
        leases(Duration.ofSeconds(10));
        try (ImapLease held = leases.lease(key, ctx())) {
            long start = System.nanoTime();
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> leases.lease(key, ctx()));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
            assertThat(t).isInstanceOf(de.alexanderlindhorst.imapmcp.core.error.ServerBusyException.class);
            assertThat(ImapErrorMapper.map(t, "rid").error().code()).isEqualTo(ErrorCode.SERVER_BUSY);
            assertThat(pool.createdCount()).isEqualTo(1); // the refused call never touched the pool
        }
        assertAllPermitsFree();
        try (ImapLease again = leases.lease(key, ctx())) {
            assertThat(again.store().isConnected()).isTrue();
        }
        assertAllPermitsFree();
    }

    @Test
    void permitReleasedAfterException() throws Exception {
        leases(Duration.ofSeconds(10));
        assertThatThrownBy(() -> {
            try (ImapLease l = leases.lease(key, ctx())) {
                l.markBroken();
                throw new IllegalStateException("boom");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertAllPermitsFree();
    }

    @Test
    void permitReleasedWhenBorrowFailsOrTimesOut() {
        leases(Duration.ofMillis(200));
        credentials = (t, a) -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        };
        assertThatThrownBy(() -> leases.lease(key, ctx())).isInstanceOf(StoreUnavailableException.class);
        assertAllPermitsFree();
        credentials = (t, a) -> Optional.empty(); // missing credential: borrow fails
        assertThatThrownBy(() -> leases.lease(key, ctx())).isInstanceOf(StoreUnavailableException.class);
        assertAllPermitsFree();
    }

    @Test
    void permitReleasedOnCancellationEvenBeforeClose() throws Exception {
        leases(Duration.ofSeconds(10));
        ToolCallContext c = ctx();
        ImapLease l = leases.lease(key, c);
        assertThat(admission.availableMailbox(key)).isZero();
        c.cancel();
        assertAllPermitsFree();
        l.close();
        assertAllPermitsFree(); // not released twice
    }

    @Test
    void permitReleasedWhenContextAlreadyCancelled() {
        leases(Duration.ofSeconds(10));
        ToolCallContext c = ctx();
        c.cancel();
        assertThatThrownBy(() -> leases.lease(key, c)).isInstanceOf(StoreUnavailableException.class);
        assertAllPermitsFree();
    }
}
