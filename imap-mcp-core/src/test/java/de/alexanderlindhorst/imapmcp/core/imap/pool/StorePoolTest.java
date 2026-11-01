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
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import jakarta.mail.Session;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StorePoolTest {

    private static final String USER = "pooluser";
    private static final String PASSWORD = "poolpass";
    private static final TenantId TENANT = new TenantId("t1");

    private GreenMail greenMail;
    private ImapSessionFactory sessions;
    private StorePool pool;
    private PoolKey key;
    private MailAccount account;
    private final AtomicInteger credentialLookups = new AtomicInteger();
    private final CapabilityCache capabilities = new CapabilityCache();
    private final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    private Logger traceLogger;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser(USER + "@localhost", USER, PASSWORD);
        account = new MailAccount(new AccountId("a"), "label", null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
        key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, account, "acc_test"));
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

    private CredentialStore credentials() {
        return (tenant, acct) -> {
            credentialLookups.incrementAndGet();
            return tenant.equals(TENANT) && acct.equals(account.id())
                    ? Optional.of(new Secret(PASSWORD.toCharArray())) : Optional.empty();
        };
    }

    private StorePool pool(PoolSettings settings) {
        pool = new StorePool(sessions, credentials(), capabilities, k -> k.equals(key) ? account : null, settings);
        return pool;
    }

    private long noops() {
        return trace.list.stream().filter(e -> e.getFormattedMessage().matches("[A-Z]+\\d+ NOOP")).count();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(25);
        }
    }

    @Test
    void defaultSettingsMatchSpec() {
        PoolSettings d = PoolSettings.DEFAULTS;
        assertThat(d.maxPerMailbox()).isEqualTo(3);
        assertThat(d.maxTotal()).isEqualTo(64);
        assertThat(d.idleEviction()).isEqualTo(Duration.ofMinutes(5));
        assertThat(d.validateAfterIdle()).isEqualTo(Duration.ofSeconds(60));
        assertThat(d.maxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(d.acquireTimeout()).isEqualTo(Duration.ofSeconds(10));
        StorePool p = pool(d);
        assertThat(p.pool().getMaxTotalPerKey()).isEqualTo(3);
        assertThat(p.pool().getMaxIdlePerKey()).isEqualTo(3);
        assertThat(p.pool().getMaxTotal()).isEqualTo(64);
        assertThat(p.pool().getBlockWhenExhausted()).isTrue();
        assertThat(p.pool().getMaxWaitDuration()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.pool().getTestOnBorrow()).isTrue();
        assertThat(p.pool().getTestWhileIdle()).isFalse();
        assertThat(p.pool().getJmxName()).isNull();
        assertThat(p.pool().getDurationBetweenEvictionRuns()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.pool().getNumTestsPerEvictionRun()).isEqualTo(-1);
    }

    @Test
    void reusesSameStoreWithoutNoopWhenRecentlyUsed() throws Exception {
        StorePool p = pool(PoolSettings.DEFAULTS);
        IMAPStore first = p.borrow(key);
        assertThat(first.isConnected()).isTrue(); // direct NOOP from the test, not the pool
        p.release(key, first);
        long before = noops();
        IMAPStore second = p.borrow(key);
        assertThat(second).isSameAs(first);
        assertThat(noops()).isEqualTo(before);
        p.release(key, second);
        assertThat(p.createdCount()).isEqualTo(1);
    }

    @Test
    void sendsNoopWhenIdleLongerThanValidateAfterIdle() throws Exception {
        StorePool p = pool(PoolSettings.DEFAULTS.withValidateAfterIdle(Duration.ofMillis(50)));
        IMAPStore first = p.borrow(key);
        p.release(key, first);
        Thread.sleep(120);
        long before = noops();
        IMAPStore second = p.borrow(key);
        assertThat(second).isSameAs(first);
        assertThat(noops()).isEqualTo(before + 1);
        p.release(key, second);
    }

    @Test
    void storeWhoseServerSideWasClosedIsRejectedAndReplaced() throws Exception {
        StorePool p = pool(PoolSettings.DEFAULTS.withValidateAfterIdle(Duration.ofMillis(50)));
        IMAPStore first = p.borrow(key);
        p.release(key, first);
        greenMail.stop();
        greenMail.start();
        greenMail.setUser(USER + "@localhost", USER, PASSWORD);
        // the restarted server may listen on another port; the account lookup is consulted on each connect
        account = new MailAccount(account.id(), "label", null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
        Thread.sleep(120);
        IMAPStore second = p.borrow(key);
        assertThat(second).isNotSameAs(first);
        assertThat(second.isConnected()).isTrue();
        p.release(key, second);
        assertThat(p.createdCount()).isEqualTo(2);
        await(() -> p.destroyedCount() == 1);
    }

    @Test
    void storeOlderThanMaxLifetimeIsNotHandedOutAndIsDestroyed() throws Exception {
        StorePool p = pool(PoolSettings.DEFAULTS.withMaxLifetime(Duration.ofMillis(150)));
        IMAPStore first = p.borrow(key);
        p.release(key, first);
        Thread.sleep(250);
        IMAPStore second = p.borrow(key);
        assertThat(second).isNotSameAs(first);
        p.release(key, second);
        await(() -> p.destroyedCount() == 1);
        await(() -> !first.isConnected());
    }

    @Test
    void evictionRemovesStoresIdleLongerThanIdleEviction() throws Exception {
        StorePool p = pool(PoolSettings.DEFAULTS.withIdleEviction(Duration.ofMillis(100))
                .withEvictionInterval(Duration.ofMillis(50)));
        p.release(key, p.borrow(key));
        assertThat(p.numIdle(key)).isEqualTo(1);
        await(() -> p.numIdle(key) == 0);
        await(() -> p.destroyedCount() == 1);
    }

    @Test
    void evictionRemovesStoresOlderThanMaxLifetime() throws Exception {
        StorePool p = pool(PoolSettings.DEFAULTS.withMaxLifetime(Duration.ofMillis(100))
                .withEvictionInterval(Duration.ofMillis(50)));
        p.release(key, p.borrow(key));
        await(() -> p.numIdle(key) == 0);
    }

    @Test
    void makeObjectResolvesCredentialOncePerConnectionAndFillsCapabilityCache() throws Exception {
        StorePool p = pool(PoolSettings.DEFAULTS);
        assertThat(capabilities.get(key)).isInstanceOf(CapabilityCache.Lookup.Missing.class);
        IMAPStore a = p.borrow(key);
        assertThat(credentialLookups.get()).isEqualTo(1);
        assertThat(capabilities.get(key)).isInstanceOf(CapabilityCache.Lookup.Fresh.class);
        p.release(key, a);
        IMAPStore b = p.borrow(key);
        assertThat(credentialLookups.get()).isEqualTo(1);
        IMAPStore c = p.borrow(key); // second concurrent lease: new connection
        assertThat(credentialLookups.get()).isEqualTo(2);
        assertThat(p.numActive(key)).isEqualTo(2);
        p.release(key, b);
        p.release(key, c);
    }

    @Test
    void unknownKeyOrMissingCredentialFailsWithoutLeaking() {
        StorePool p = pool(PoolSettings.DEFAULTS);
        PoolKey other = PoolKey.of(new AccountContext(new TenantId("t2")),
                new ResolvedAccount(new TenantId("t2"), account, "acc_test"));
        assertThatThrownBy(() -> p.borrow(other)).isInstanceOf(Exception.class);
        assertThat(p.numActive(other)).isZero();
        assertThat(credentialLookups.get()).isZero();
    }

    @Test
    void invalidateDestroysInsteadOfReturning() throws Exception {
        StorePool p = pool(PoolSettings.DEFAULTS);
        IMAPStore a = p.borrow(key);
        p.invalidate(key, a);
        assertThat(p.numIdle(key)).isZero();
        assertThat(p.numActive(key)).isZero();
        await(() -> p.destroyedCount() == 1);
    }

    @Test
    void destroyObjectReturnsImmediatelyEvenIfCloseBlocks() throws Exception {
        StorePoolFactory factory = new StorePoolFactory(sessions, credentials(), capabilities,
                k -> account, PoolSettings.DEFAULTS);
        Session session = Session.getInstance(new java.util.Properties());
        AtomicInteger closed = new AtomicInteger();
        IMAPStore slow = new IMAPStore(session, null) {
            @Override
            public synchronized void close() {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                closed.incrementAndGet();
            }
        };
        long start = System.nanoTime();
        factory.destroyObject(key, new DefaultPooledObject<>(slow));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        assertThat(closed.get()).isZero();
    }
}
