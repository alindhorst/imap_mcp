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
        assertThat(d.maxPerTenant()).isEqualTo(8);
        assertThat(d.abandonedTimeout()).isEqualTo(Duration.ofSeconds(120));
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
        assertThat(p.pool().getRemoveAbandonedOnMaintenance()).isTrue();
        assertThat(p.pool().getRemoveAbandonedTimeoutDuration()).isEqualTo(Duration.ofSeconds(120));
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

    private MailAccount accountAt(long revision, MailAccount.Status status) {
        return new MailAccount(account.id(), "label", null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"), false, false,
                MailAccount.Auth.PASSWORD, status, revision);
    }

    private PoolKey keyFor(MailAccount a) {
        return PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, a, "acc_test"));
    }

    @Test
    void poolKeyCarriesRevisionButEqualityIgnoresIt() {
        PoolKey r0 = keyFor(accountAt(0, MailAccount.Status.ACTIVE));
        PoolKey r5 = keyFor(accountAt(5, MailAccount.Status.ACTIVE));
        assertThat(r5.revision()).isEqualTo(5L);
        assertThat(r5).isEqualTo(r0).hasSameHashCodeAs(r0);
    }

    @Test
    void borrowedStoreWithLowerRevisionIsInvalidatedAndBorrowRepeated() throws Exception {
        account = accountAt(0, MailAccount.Status.ACTIVE);
        StorePool p = pool(PoolSettings.DEFAULTS);
        PoolKey k0 = keyFor(account);
        IMAPStore first = p.borrow(k0);
        p.release(k0, first);
        account = accountAt(1, MailAccount.Status.ACTIVE); // e.g. credentials changed
        PoolKey k1 = keyFor(account);
        IMAPStore second = p.borrow(k1);
        assertThat(second).isNotSameAs(first);
        assertThat(second.isConnected()).isTrue();
        p.release(k1, second);
        assertThat(p.createdCount()).isEqualTo(2);
        await(() -> p.destroyedCount() == 1);
        await(() -> !first.isConnected());
        assertThat(p.numIdle(k1)).isEqualTo(1);
    }

    @Test
    void storeWithEqualOrHigherRevisionIsReused() throws Exception {
        account = accountAt(3, MailAccount.Status.ACTIVE);
        StorePool p = pool(PoolSettings.DEFAULTS);
        IMAPStore first = p.borrow(keyFor(account));
        p.release(keyFor(account), first);
        IMAPStore same = p.borrow(keyFor(accountAt(3, MailAccount.Status.ACTIVE)));
        assertThat(same).isSameAs(first);
        p.release(keyFor(account), same);
        IMAPStore older = p.borrow(keyFor(accountAt(2, MailAccount.Status.ACTIVE)));
        assertThat(older).isSameAs(first);
        p.release(keyFor(account), older);
        assertThat(p.createdCount()).isEqualTo(1);
    }

    @Test
    void borrowGivesUpWhenNoStoreWithTheRequestedRevisionCanBeMade() throws Exception {
        account = accountAt(0, MailAccount.Status.ACTIVE); // the lookup is behind what the call resolved
        StorePool p = pool(PoolSettings.DEFAULTS);
        PoolKey ahead = keyFor(accountAt(9, MailAccount.Status.ACTIVE));
        assertThatThrownBy(() -> p.borrow(ahead)).isInstanceOf(Exception.class);
        assertThat(p.createdCount()).isLessThanOrEqualTo(3);
        assertThat(p.numActive(ahead)).isZero();
        assertThat(p.numIdle(ahead)).isZero();
    }

    @Test
    void noConnectionIsMadeForNeedsRelinkAccount() {
        account = accountAt(1, MailAccount.Status.NEEDS_RELINK);
        StorePool p = pool(PoolSettings.DEFAULTS);
        PoolKey k = keyFor(account);
        assertThatThrownBy(() -> p.borrow(k)).isInstanceOf(Exception.class);
        assertThat(credentialLookups.get()).isZero();
        assertThat(p.createdCount()).isZero();
        assertThat(p.numActive(k)).isZero();
    }

    @Test
    void storeIdleBeforeRelinkIsNotHandedOutAfterTheStatusChange() throws Exception {
        account = accountAt(0, MailAccount.Status.ACTIVE);
        StorePool p = pool(PoolSettings.DEFAULTS);
        IMAPStore first = p.borrow(keyFor(account));
        p.release(keyFor(account), first);
        account = accountAt(1, MailAccount.Status.NEEDS_RELINK);
        PoolKey k1 = keyFor(account);
        assertThatThrownBy(() -> p.borrow(k1)).isInstanceOf(Exception.class);
        assertThat(credentialLookups.get()).isEqualTo(1); // only the first connect
        await(() -> p.destroyedCount() == 1);
    }

    @Test
    void poolLooksAccountsUpThroughTheRepository() throws Exception {
        MailAccount stored = accountAt(0, MailAccount.Status.ACTIVE);
        de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository repo =
                new de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository(
                        java.util.Map.of(TENANT, java.util.List.of(stored)));
        pool = new StorePool(sessions, credentials(), capabilities, repo, PoolSettings.DEFAULTS);
        PoolKey k = keyFor(stored);
        IMAPStore store = pool.borrow(k);
        assertThat(store.isConnected()).isTrue();
        pool.release(k, store);
        // another tenant's key for the same account id finds nothing in the repository
        PoolKey foreign = PoolKey.of(new AccountContext(new TenantId("t2")),
                new ResolvedAccount(new TenantId("t2"), stored, "acc_test"));
        assertThatThrownBy(() -> pool.borrow(foreign)).isInstanceOf(Exception.class);
    }
}
