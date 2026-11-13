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
import de.alexanderlindhorst.imapmcp.core.account.LoginOutcomes;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Auth;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Status;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.Xoauth2RejectedException;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.testsupport.FakeTokens;
import de.alexanderlindhorst.imapmcp.core.testsupport.ScriptedImapsServer;
import de.alexanderlindhorst.imapmcp.core.testsupport.ScriptedImapsServer.AuthAnswer;
import de.alexanderlindhorst.imapmcp.core.testsupport.WireTrace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The pool side of XOAUTH2 accounts (SPEC 5.5 "Pool"): token as password, expiry of Stores, no counted refusals. */
class StorePoolXoauth2Test {

    private static final String USER = "user@example.org";
    private static final TenantId TENANT = new TenantId("t1");
    private static final String CANARY = "canary-token-7f3a9c";
    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");

    /** A clock the tests move by hand. */
    static final class FakeClock extends Clock {
        volatile Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final FakeClock clock = new FakeClock();
    private final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    private final AtomicInteger credentialLookups = new AtomicInteger();
    private final AtomicInteger refused = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private Logger traceLogger;
    private GreenMail greenMail;
    private ImapSessionFactory sessions;
    private StorePool pool;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser(USER, USER, CANARY);
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

    @AfterEach
    void tearDown() {
        traceLogger.detachAppender(trace);
        if (pool != null) {
            pool.close();
        }
        sessions.close();
        greenMail.stop();
    }

    private MailAccount account(int port) {
        return new MailAccount(new AccountId("a"), "label", USER, "127.0.0.1", port, Security.IMPLICIT_TLS,
                Flavor.GENERIC, USER, new SecretRef("db:"), false, false, Auth.XOAUTH2, Status.ACTIVE, 0L);
    }

    private static PoolKey key(MailAccount a, TenantId tenant) {
        return PoolKey.of(new AccountContext(tenant), new ResolvedAccount(tenant, a, "acc_test"));
    }

    private StorePool pool(MailAccount a, FakeTokens tokens) {
        CredentialStore credentials = (t, id) -> {
            credentialLookups.incrementAndGet();
            return java.util.Optional.empty();
        };
        LoginOutcomes outcomes = new LoginOutcomes() {
            @Override
            public void refused(TenantId tenant, AccountId account, long revision) {
                refused.incrementAndGet();
            }

            @Override
            public void succeeded(TenantId tenant, AccountId account, long revision) {
                succeeded.incrementAndGet();
            }
        };
        pool = new StorePool(sessions, credentials, new CapabilityCache(),
                k -> k.tenantId().equals(TENANT) && k.accountId().equals(a.id()) ? a : null, PoolSettings.DEFAULTS,
                MetricsRecorder.NOOP, outcomes, tokens, clock);
        return pool;
    }

    @Test
    void connectsWithTheTokenAndNeverTouchesTheCredentialStore() throws Exception {
        MailAccount a = account(greenMail.getImaps().getPort());
        FakeTokens tokens = new FakeTokens(CANARY, T0.plus(Duration.ofHours(1)));
        StorePool p = pool(a, tokens);
        IMAPStore store = p.borrow(key(a, TENANT));
        assertThat(store.isConnected()).isTrue();
        p.release(key(a, TENANT), store);
        assertThat(credentialLookups).hasValue(0);
        assertThat(tokens.tenantsSeen()).containsOnly(TENANT);
        assertThat(succeeded).hasValue(1);
    }

    @Test
    void aStoreWhoseTokenIsAboutToExpireIsNotReusedButANearExpiryOneBeyondTheMarginIs() throws Exception {
        MailAccount a = account(greenMail.getImaps().getPort());
        FakeTokens tokens = new FakeTokens(CANARY, T0.plus(Duration.ofMinutes(10)));
        StorePool p = pool(a, tokens);
        PoolKey key = key(a, TENANT);
        Instant deadline = T0.plusSeconds(30);

        IMAPStore first = p.borrow(key, deadline);
        p.release(key, first);
        clock.now = T0.plus(Duration.ofMinutes(8)); // 2 min left: more than deadline (30 s) + 60 s margin
        IMAPStore again = p.borrow(key, clock.now.plusSeconds(30));
        assertThat(again).isSameAs(first);
        p.release(key, again);

        clock.now = T0.plus(Duration.ofMinutes(10)).minusSeconds(80); // 80 s left < deadline 30 s + 60 s
        tokens.setCurrent(CANARY, clock.now.plus(Duration.ofHours(1))); // what the cache would have refreshed
        IMAPStore fresh = p.borrow(key, clock.now.plusSeconds(30));
        assertThat(fresh).isNotSameAs(first);
        assertThat(p.createdCount()).isEqualTo(2);
        p.release(key, fresh);
    }

    @Test
    void aStoreWhoseTokenHasExpiredIsEvictedWhileIdle() throws Exception {
        MailAccount a = account(greenMail.getImaps().getPort());
        FakeTokens tokens = new FakeTokens(CANARY, T0.plus(Duration.ofMinutes(10)));
        StorePool p = pool(a, tokens);
        PoolKey key = key(a, TENANT);
        p.release(key, p.borrow(key, T0.plusSeconds(30)));
        assertThat(p.numIdle(key)).isEqualTo(1);
        clock.now = T0.plus(Duration.ofMinutes(10));
        p.pool().evict();
        assertThat(p.numIdle(key)).isZero();
    }

    @Test
    void aRejectedTokenIsXoauth2RejectedAndNeverCountedAsARefusedLogin() throws Exception {
        MailAccount a = account(greenMail.getImaps().getPort());
        FakeTokens tokens = new FakeTokens("wrong-1", T0.plus(Duration.ofHours(1))).renewedTo("wrong-2",
                T0.plus(Duration.ofHours(1)));
        StorePool p = pool(a, tokens);
        assertThatThrownBy(() -> p.borrow(key(a, TENANT))).isInstanceOf(Xoauth2RejectedException.class);
        assertThat(tokens.renewals()).isEqualTo(1);
        assertThat(refused).hasValue(0);
        assertThat(succeeded).hasValue(0);
    }

    @Test
    void aForeignTenantKeyGetsNoAccountAndNoToken() {
        MailAccount a = account(greenMail.getImaps().getPort());
        FakeTokens tokens = new FakeTokens(CANARY, T0.plus(Duration.ofHours(1)));
        StorePool p = pool(a, tokens);
        PoolKey foreign = key(a, new TenantId("t2")); // the tenant-scoped lookup does not know it
        assertThatThrownBy(() -> p.borrow(foreign)).hasMessageContaining("no account");
        assertThat(tokens.currentCalls()).isZero();
    }

    @Test
    void theTraceNeverShowsTheTokenOrItsBase64() throws Exception {
        MailAccount a = account(greenMail.getImaps().getPort());
        FakeTokens tokens = new FakeTokens(CANARY, T0.plus(Duration.ofHours(1)));
        StorePool p = pool(a, tokens);
        PoolKey key = key(a, TENANT);
        p.release(key, p.borrow(key));
        assertNoCanary();
        assertThat(WireTrace.lines(trace)).anyMatch(l -> l.equals("<redacted>")); // the AUTHENTICATE line
    }

    @Test
    void theErrorChallengeExchangeIsRedactedInTheTrace() throws Exception {
        try (ScriptedImapsServer server = new ScriptedImapsServer("IMAP4rev1 SASL-IR AUTH=XOAUTH2",
                AuthAnswer.CHALLENGE_THEN_NO)) {
            MailAccount a = account(server.port());
            FakeTokens tokens = new FakeTokens(CANARY, T0.plus(Duration.ofHours(1))).renewedTo(CANARY + "2",
                    T0.plus(Duration.ofHours(1)));
            StorePool p = pool(a, tokens);
            assertThatThrownBy(() -> p.borrow(key(a, TENANT))).isInstanceOf(Xoauth2RejectedException.class);
            assertNoCanary();
            assertThat(WireTrace.lines(trace)).anyMatch(l -> l.equals("<redacted>"));
            // the challenge itself (base64 JSON status) and the client's answer to it are not in the trace
            assertThat(WireTrace.lines(trace)).noneMatch(l -> l.contains("eyJzdGF0dXMi"));
        }
    }

    private void assertNoCanary() {
        String b64 = java.util.Base64.getEncoder().encodeToString(
                ("user=" + USER + "\1auth=Bearer " + CANARY).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (String l : WireTrace.lines(trace)) {
            assertThat(l).doesNotContain(CANARY).doesNotContain(b64.substring(0, 24));
        }
    }
}
