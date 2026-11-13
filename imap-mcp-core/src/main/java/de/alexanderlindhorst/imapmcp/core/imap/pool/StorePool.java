package de.alexanderlindhorst.imapmcp.core.imap.pool;

import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.LoginOutcomes;
import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.metrics.PoolGauges;
import jakarta.mail.MessagingException;
import de.alexanderlindhorst.imapmcp.core.oauth.OAuthAccessTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.AbandonedConfig;
import org.apache.commons.pool2.impl.EvictionConfig;
import org.apache.commons.pool2.impl.EvictionPolicy;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * Keyed pool of {@link IMAPStore}s, one key per {@code (tenant, account)} (SPEC 5.5). A borrowed Store belongs to
 * exactly one tool call and must never be shared across threads; on timeout, cancellation or a closed
 * folder/store the caller must {@link #invalidate} instead of {@link #release}.
 */
public final class StorePool implements AutoCloseable, PoolGauges {

    /** Borrow attempts before giving up on a Store of the revision the call resolved. */
    private static final int REVISION_ATTEMPTS = 3;

    private final GenericKeyedObjectPool<PoolKey, IMAPStore> pool;
    private final StorePoolFactory factory;

    /**
     * Pool whose account lookup is the tenant-scoped {@code findByTenantAndId} of the repository, in every mode
     * (SPEC 5.5); there is no startup map of accounts.
     */
    public StorePool(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            AccountRepository repository, PoolSettings s, MetricsRecorder metrics) {
        this(sessions, credentials, capabilities, lookup(repository), s, metrics);
    }

    public StorePool(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            AccountRepository repository, PoolSettings s) {
        this(sessions, credentials, capabilities, repository, s, MetricsRecorder.NOOP);
    }

    private static Function<PoolKey, MailAccount> lookup(AccountRepository repository) {
        Objects.requireNonNull(repository, "repository");
        return key -> repository.findByTenantAndId(key.tenantId(), key.accountId())
                .filter(r -> r.tenantId().equals(key.tenantId())) // defence in depth
                .map(ResolvedAccount::account).orElse(null);
    }

    /** Tests only: production wiring passes the repository, so no startup map of accounts exists. */
    public StorePool(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings s) {
        this(sessions, credentials, capabilities, accounts, s, MetricsRecorder.NOOP);
    }

    /** {@code metrics} receives the connect+login latency of every new Store. */
    public StorePool(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings s, MetricsRecorder metrics) {
        this(sessions, credentials, capabilities, accounts, s, metrics, LoginOutcomes.NONE);
    }

    /** As the repository variant, with the login outcomes of SPEC 5.3 "Re-link". */
    public StorePool(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            AccountRepository repository, PoolSettings s, MetricsRecorder metrics, LoginOutcomes outcomes) {
        this(sessions, credentials, capabilities, lookup(repository), s, metrics, outcomes);
    }

    /** {@code outcomes} receives every real LOGIN result (refused without [UNAVAILABLE], or success). */
    public StorePool(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings s, MetricsRecorder metrics,
            LoginOutcomes outcomes) {
        this(sessions, credentials, capabilities, accounts, s, metrics, outcomes, null, Clock.systemUTC());
    }

    /**
     * {@code tokens} (nullable: an XOAUTH2 account then cannot connect) supplies the access tokens of XOAUTH2
     * accounts (SPEC 5.5); {@code clock} is a test seam for their expiry.
     */
    StorePool(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings s, MetricsRecorder metrics,
            LoginOutcomes outcomes, OAuthAccessTokens tokens, Clock clock) {
        GenericKeyedObjectPoolConfig<IMAPStore> config = new GenericKeyedObjectPoolConfig<>();
        config.setJmxEnabled(false); // must be set before construction
        config.setMaxTotalPerKey(s.maxPerMailbox());
        config.setMaxIdlePerKey(s.maxPerMailbox());
        config.setMaxTotal(s.maxTotal());
        config.setBlockWhenExhausted(true);
        config.setMaxWait(s.acquireTimeout());
        config.setTestOnBorrow(true);
        config.setTestWhileIdle(false);
        config.setTimeBetweenEvictionRuns(s.evictionInterval());
        config.setNumTestsPerEvictionRun(-1);
        this.factory = new StorePoolFactory(sessions, credentials, capabilities, accounts, s, metrics, outcomes, tokens,
                clock);
        config.setEvictionPolicy(new LifetimeEvictionPolicy(s.idleEviction(), s.maxLifetime(), factory::tokenExpired));
        this.pool = new GenericKeyedObjectPool<>(factory, config);
        AbandonedConfig abandoned = new AbandonedConfig();
        abandoned.setRemoveAbandonedOnMaintenance(true);
        abandoned.setRemoveAbandonedTimeout(s.abandonedTimeout());
        pool.setAbandonedConfig(abandoned);
    }

    /** Evicts idle stores that are idle longer than {@code idleEviction} or older than {@code maxLifetime}. */
    static final class LifetimeEvictionPolicy implements EvictionPolicy<IMAPStore> {
        private final Duration idleEviction;
        private final Duration maxLifetime;
        private final Predicate<IMAPStore> tokenExpired;

        LifetimeEvictionPolicy(Duration idleEviction, Duration maxLifetime, Predicate<IMAPStore> tokenExpired) {
            this.idleEviction = idleEviction;
            this.maxLifetime = maxLifetime;
            this.tokenExpired = tokenExpired;
        }

        @Override
        public boolean evict(EvictionConfig config, PooledObject<IMAPStore> p, int idleCount) {
            return p.getIdleDuration().compareTo(idleEviction) > 0
                    || StorePoolFactory.age(p).compareTo(maxLifetime) > 0
                    || tokenExpired.test(p.getObject());
        }
    }

    /**
     * Borrows a Store made for at least {@code key.revision()}: an older one (credentials or status changed since)
     * is invalidated and the borrow repeated, never used (SPEC 5.5).
     */
    public IMAPStore borrow(PoolKey key) throws Exception {
        return borrow(key, null);
    }

    /** As {@link #borrow(PoolKey)}; {@code deadline} (nullable) is the tool call's deadline. */
    public IMAPStore borrow(PoolKey key, Instant deadline) throws Exception {
        if (deadline == null) {
            return borrowBound(key);
        }
        return ScopedValue.where(StorePoolFactory.CALL_DEADLINE, deadline).call(() -> borrowBound(key));
    }

    private IMAPStore borrowBound(PoolKey key) throws Exception {
        if (!key.active()) { // the call resolved an account that needs a re-link: no connection, no idle Store
            pool.clear(key);
            throw new AccountNeedsRelinkException();
        }
        for (int attempt = 0; attempt < REVISION_ATTEMPTS; attempt++) {
            IMAPStore store = pool.borrowObject(key);
            if (factory.revisionOf(store) >= key.revision()) {
                return store;
            }
            pool.invalidateObject(key, store);
            pool.clear(key); // the other idle Stores of the key are as old; drop them in one go
        }
        throw new MessagingException("no connection for the current account revision");
    }

    public void release(PoolKey key, IMAPStore store) {
        pool.returnObject(key, store);
    }

    public void invalidate(PoolKey key, IMAPStore store) throws Exception {
        pool.invalidateObject(key, store);
    }

    @Override
    public int numActive(PoolKey key) {
        return pool.getNumActive(key);
    }

    @Override
    public int numIdle(PoolKey key) {
        return pool.getNumIdle(key);
    }

    @Override
    public long createdCount() {
        return pool.getCreatedCount();
    }

    @Override
    public long destroyedCount() {
        return pool.getDestroyedCount();
    }

    /** The underlying pool, for configuration inspection and metrics. */
    GenericKeyedObjectPool<PoolKey, IMAPStore> pool() {
        return pool;
    }

    @Override
    public void close() {
        pool.close();
    }
}
