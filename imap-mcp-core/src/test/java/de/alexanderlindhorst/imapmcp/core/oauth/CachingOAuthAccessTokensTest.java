package de.alexanderlindhorst.imapmcp.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CachingOAuthAccessTokensTest {

    static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    static final TenantId TENANT_A = new TenantId("tenant-a");
    static final TenantId TENANT_B = new TenantId("tenant-b");
    static final AccountId ACC = new AccountId("acc1");

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

    final FakeClock clock = new FakeClock();
    final AtomicInteger calls = new AtomicInteger();
    final List<CachingOAuthAccessTokens> created = new ArrayList<>();
    final ExecutorService callers = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        created.forEach(CachingOAuthAccessTokens::close);
        callers.shutdownNow();
    }

    CachingOAuthAccessTokens cache(RefreshGrant grant, int maxEntries, int maxConcurrent) {
        CachingOAuthAccessTokens c = new CachingOAuthAccessTokens(grant, clock, maxEntries, maxConcurrent, 64);
        created.add(c);
        return c;
    }

    /** A grant that returns tokens valid for one hour from the clock, valued {@code tenant/account/n}. */
    RefreshGrant okGrant() {
        return (t, a) -> {
            int n = calls.incrementAndGet();
            return new RefreshResult.Refreshed(token(t.value() + "/" + a.value() + "/" + n, clock.instant().plus(Duration.ofHours(1))));
        };
    }

    static AccessToken token(String value, Instant expiresAt) {
        return new AccessToken(new Secret(value.toCharArray()), expiresAt);
    }

    static String value(AccessToken t) {
        return new String(t.value().chars());
    }

    Instant deadline() {
        return clock.instant().plusSeconds(30);
    }

    @Test
    void concurrentCurrentCallsForOneKeyCauseExactlyOneRefresh() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        RefreshGrant slow = (t, a) -> {
            calls.incrementAndGet();
            await(release);
            return new RefreshResult.Refreshed(token("tok", clock.instant().plus(Duration.ofHours(1))));
        };
        CachingOAuthAccessTokens c = cache(slow, 10, 4);
        List<Future<AccessToken>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(callers.submit(() -> c.current(TENANT_A, ACC, deadline())));
        }
        Thread.sleep(200);
        release.countDown();
        AccessToken first = results.get(0).get(5, TimeUnit.SECONDS);
        for (Future<AccessToken> f : results) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isSameAs(first);
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void differentKeysRefreshIndependentlyAndAreCached() {
        CachingOAuthAccessTokens c = cache(okGrant(), 10, 4);
        c.current(TENANT_A, new AccountId("a1"), deadline());
        c.current(TENANT_A, new AccountId("a2"), deadline());
        assertThat(calls.get()).isEqualTo(2);
        c.current(TENANT_A, new AccountId("a1"), deadline());
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void sameAccountIdUnderTwoTenantsNeverSharesAToken() {
        CachingOAuthAccessTokens c = cache(okGrant(), 10, 4);
        AccessToken a = c.current(TENANT_A, ACC, deadline());
        AccessToken b = c.current(TENANT_B, ACC, deadline());
        assertThat(value(a)).startsWith("tenant-a/");
        assertThat(value(b)).startsWith("tenant-b/");
        assertThat(c.current(TENANT_A, ACC, deadline())).isSameAs(a);
        assertThat(c.current(TENANT_B, ACC, deadline())).isSameAs(b);
    }

    @Test
    void refreshesFiveMinutesBeforeExpiry() {
        CachingOAuthAccessTokens c = cache(okGrant(), 10, 4);
        AccessToken first = c.current(TENANT_A, ACC, deadline());
        clock.now = T0.plus(Duration.ofMinutes(54));
        assertThat(c.current(TENANT_A, ACC, deadline())).isSameAs(first);
        assertThat(calls.get()).isEqualTo(1);
        clock.now = T0.plus(Duration.ofMinutes(56));
        AccessToken second = c.current(TENANT_A, ACC, deadline());
        assertThat(second).isNotSameAs(first);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void endpointFailureWhileCachedTokenIsValidBeyondTheDeadlineReturnsTheCachedToken() {
        AtomicReference<RefreshResult> next = new AtomicReference<>();
        RefreshGrant grant = (t, a) -> {
            calls.incrementAndGet();
            RefreshResult r = next.get();
            return r != null ? r : new RefreshResult.Refreshed(token("tok", clock.instant().plus(Duration.ofHours(1))));
        };
        CachingOAuthAccessTokens c = cache(grant, 10, 4);
        AccessToken first = c.current(TENANT_A, ACC, deadline());
        clock.now = T0.plus(Duration.ofMinutes(56)); // inside the early-refresh window, still valid for 4 min
        next.set(new RefreshResult.Unavailable(Duration.ofSeconds(5)));
        assertThat(c.current(TENANT_A, ACC, clock.instant().plusSeconds(30))).isSameAs(first);
        assertThat(calls.get()).isEqualTo(2);
        // the token expires before the deadline: the failure is reported
        assertThatThrownBy(() -> c.current(TENANT_A, ACC, clock.instant().plus(Duration.ofMinutes(10))))
                .isInstanceOfSatisfying(TokenAcquisitionException.class,
                        e -> assertThat(e.toolError(null, "r").code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    }

    @Test
    void waiterWhoseDeadlineExpiresGetsTimeoutAndTheRefreshStillUpdatesTheCache() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        RefreshGrant slow = (t, a) -> {
            calls.incrementAndGet();
            await(release);
            RefreshResult r = new RefreshResult.Refreshed(token("tok", clock.instant().plus(Duration.ofHours(1))));
            done.countDown();
            return r;
        };
        CachingOAuthAccessTokens c = cache(slow, 10, 4);
        assertThatThrownBy(() -> c.current(TENANT_A, ACC, clock.instant().plusMillis(100)))
                .isInstanceOfSatisfying(TokenAcquisitionException.class,
                        e -> assertThat(e.toolError(null, "r").code()).isEqualTo(ErrorCode.TIMEOUT));
        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        AccessToken t = c.current(TENANT_A, ACC, deadline());
        assertThat(value(t)).isEqualTo("tok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void interruptedWaiterGetsTimeoutKeepsItsInterruptFlagAndDoesNotCancelTheRefresh() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        RefreshGrant slow = (t, a) -> {
            calls.incrementAndGet();
            await(release);
            done.countDown();
            return new RefreshResult.Refreshed(token("tok", clock.instant().plus(Duration.ofHours(1))));
        };
        CachingOAuthAccessTokens c = cache(slow, 10, 4);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                c.current(TENANT_A, ACC, deadline());
            } catch (Throwable e) {
                thrown.set(e);
                flag.set(Thread.currentThread().isInterrupted());
            }
            finished.countDown();
        });
        Thread.sleep(200);
        waiter.interrupt();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(thrown.get()).isInstanceOfSatisfying(TokenAcquisitionException.class,
                e -> assertThat(e.toolError(null, "r").code()).isEqualTo(ErrorCode.TIMEOUT));
        assertThat(flag.get()).isTrue();
        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void concurrentRefreshesNeverExceedTheCap() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        RefreshGrant grant = (t, a) -> {
            int now = running.incrementAndGet();
            max.accumulateAndGet(now, Math::max);
            sleep(150);
            running.decrementAndGet();
            return new RefreshResult.Refreshed(token("tok", clock.instant().plus(Duration.ofHours(1))));
        };
        CachingOAuthAccessTokens c = cache(grant, 10, 2);
        List<Future<AccessToken>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            AccountId id = new AccountId("acc" + i);
            fs.add(callers.submit(() -> c.current(TENANT_A, id, deadline())));
        }
        for (Future<AccessToken> f : fs) {
            f.get(10, TimeUnit.SECONDS);
        }
        assertThat(max.get()).isEqualTo(2);
    }

    @Test
    void saturatedExecutorIsServerBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        RefreshGrant blocked = (t, a) -> {
            await(release);
            return new RefreshResult.Refreshed(token("tok", clock.instant().plus(Duration.ofHours(1))));
        };
        CachingOAuthAccessTokens c = new CachingOAuthAccessTokens(blocked, clock, 10, 1, 1);
        created.add(c);
        callers.submit(() -> c.current(TENANT_A, new AccountId("a1"), deadline()));
        callers.submit(() -> c.current(TENANT_A, new AccountId("a2"), deadline()));
        Thread.sleep(300);
        assertThatThrownBy(() -> c.current(TENANT_A, new AccountId("a3"), deadline()))
                .isInstanceOfSatisfying(TokenAcquisitionException.class,
                        e -> assertThat(e.toolError(null, "r").code()).isEqualTo(ErrorCode.SERVER_BUSY));
        release.countDown();
    }

    @Test
    void everyFailureTypeSurfacesWithItsMapping() {
        BiFunction<RefreshResult, ErrorCode, Void> check = (result, code) -> {
            CachingOAuthAccessTokens c = cache((t, a) -> result, 10, 2);
            assertThatThrownBy(() -> c.current(TENANT_A, ACC, deadline()))
                    .isInstanceOfSatisfying(TokenAcquisitionException.class, e -> {
                        ToolError te = e.toolError("https://link", "r");
                        assertThat(te.code()).isEqualTo(code);
                        assertThat(te.retryable()).isEqualTo(code.retryable());
                    });
            return null;
        };
        check.apply(new RefreshResult.InvalidGrant(), ErrorCode.AUTH_FAILED);
        check.apply(new RefreshResult.ClientRejected(), ErrorCode.UPSTREAM_ERROR);
        check.apply(new RefreshResult.Unavailable(Duration.ofSeconds(3)), ErrorCode.UPSTREAM_UNAVAILABLE);
        check.apply(new RefreshResult.LockTimeout(), ErrorCode.SERVER_BUSY);
        check.apply(new RefreshResult.ProviderUnconfigured(), ErrorCode.UPSTREAM_ERROR);
    }

    @Test
    void aFailureIsNotCachedTheNextCallRefreshesAgain() {
        AtomicInteger n = new AtomicInteger();
        RefreshGrant grant = (t, a) -> n.incrementAndGet() == 1 ? new RefreshResult.LockTimeout()
                : new RefreshResult.Refreshed(token("tok", clock.instant().plus(Duration.ofHours(1))));
        CachingOAuthAccessTokens c = cache(grant, 10, 2);
        assertThatThrownBy(() -> c.current(TENANT_A, ACC, deadline())).isInstanceOf(TokenAcquisitionException.class);
        assertThat(value(c.current(TENANT_A, ACC, deadline()))).isEqualTo("tok");
    }

    @Test
    void renewAfterRejectionWithAStaleRejectedTokenReturnsTheNewerCachedTokenWithoutRefresh() {
        CachingOAuthAccessTokens c = cache(okGrant(), 10, 4);
        AccessToken t1 = c.current(TENANT_A, ACC, deadline());
        clock.now = T0.plus(Duration.ofMinutes(56));
        AccessToken t2 = c.current(TENANT_A, ACC, deadline());
        assertThat(calls.get()).isEqualTo(2);
        assertThat(c.renewAfterRejection(TENANT_A, ACC, t1, deadline())).isSameAs(t2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void renewAfterRejectionOfTheCachedTokenRefreshesOnce() {
        CachingOAuthAccessTokens c = cache(okGrant(), 10, 4);
        AccessToken t1 = c.current(TENANT_A, ACC, deadline());
        AccessToken t2 = c.renewAfterRejection(TENANT_A, ACC, t1, deadline());
        assertThat(t2).isNotSameAs(t1);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(c.current(TENANT_A, ACC, deadline())).isSameAs(t2);
    }

    @Test
    void renewAfterRejectionNeverFallsBackToTheRejectedToken() {
        AtomicReference<RefreshResult> next = new AtomicReference<>();
        RefreshGrant grant = (t, a) -> {
            RefreshResult r = next.get();
            return r != null ? r : new RefreshResult.Refreshed(token("tok", clock.instant().plus(Duration.ofHours(1))));
        };
        CachingOAuthAccessTokens c = cache(grant, 10, 2);
        AccessToken t1 = c.current(TENANT_A, ACC, deadline());
        next.set(new RefreshResult.Unavailable(null));
        assertThatThrownBy(() -> c.renewAfterRejection(TENANT_A, ACC, t1, deadline()))
                .isInstanceOf(TokenAcquisitionException.class);
    }

    @Test
    void theCacheIsBoundedAndEvictsTheLeastRecentlyUsedKey() {
        CachingOAuthAccessTokens c = cache(okGrant(), 2, 2);
        c.current(TENANT_A, new AccountId("a1"), deadline());
        c.current(TENANT_A, new AccountId("a2"), deadline());
        c.current(TENANT_A, new AccountId("a3"), deadline());
        assertThat(c.cachedEntries()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(3);
        c.current(TENANT_A, new AccountId("a1"), deadline()); // evicted: refreshes again
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void noTokenValueInAnyLogLineOrToString() {
        Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        Level before = root.getLevel();
        root.setLevel(Level.TRACE);
        try {
            String canary = "CANARY-ACCESS-TOKEN-9f3a";
            AtomicInteger n = new AtomicInteger();
            RefreshGrant grant = (t, a) -> {
                if (n.incrementAndGet() == 1) {
                    throw new IllegalStateException("boom " + canary);
                }
                return new RefreshResult.Refreshed(token(canary, clock.instant().plus(Duration.ofHours(1))));
            };
            CachingOAuthAccessTokens c = cache(grant, 10, 2);
            assertThatThrownBy(() -> c.current(TENANT_A, ACC, deadline())).isInstanceOfSatisfying(
                    TokenAcquisitionException.class, e -> {
                        assertThat(e.toString()).doesNotContain(canary);
                        assertThat(e.toolError(null, "r").toMap().toString()).doesNotContain(canary);
                    });
            AccessToken t = c.current(TENANT_A, ACC, deadline());
            assertThat(t.toString()).doesNotContain(canary);
            c.renewAfterRejection(TENANT_A, ACC, t, deadline());
            assertThat(c.toString()).doesNotContain(canary);
            assertThat(appender.list).isNotEmpty(); // the unexpected failure of the grant was logged
            for (ILoggingEvent e : appender.list) {
                assertThat(e.getFormattedMessage()).doesNotContain(canary);
                assertThat(e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage()).doesNotContain(canary);
                assertThat(e.getThrowableProxy()).isNull();
            }
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }
    }

    private static void await(CountDownLatch l) {
        try {
            l.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
