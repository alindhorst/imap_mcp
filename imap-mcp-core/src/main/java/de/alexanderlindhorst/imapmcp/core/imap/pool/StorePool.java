package de.alexanderlindhorst.imapmcp.core.imap.pool;

import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.metrics.PoolGauges;
import java.time.Duration;
import java.util.function.Function;
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

    private final GenericKeyedObjectPool<PoolKey, IMAPStore> pool;

    public StorePool(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings s) {
        this(sessions, credentials, capabilities, accounts, s, MetricsRecorder.NOOP);
    }

    /** {@code metrics} receives the connect+login latency of every new Store. */
    public StorePool(ImapSessionFactory sessions, CredentialStore credentials, CapabilityCache capabilities,
            Function<PoolKey, MailAccount> accounts, PoolSettings s, MetricsRecorder metrics) {
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
        config.setEvictionPolicy(new LifetimeEvictionPolicy(s.idleEviction(), s.maxLifetime()));
        this.pool = new GenericKeyedObjectPool<>(
                new StorePoolFactory(sessions, credentials, capabilities, accounts, s, metrics), config);
        AbandonedConfig abandoned = new AbandonedConfig();
        abandoned.setRemoveAbandonedOnMaintenance(true);
        abandoned.setRemoveAbandonedTimeout(s.abandonedTimeout());
        pool.setAbandonedConfig(abandoned);
    }

    /** Evicts idle stores that are idle longer than {@code idleEviction} or older than {@code maxLifetime}. */
    static final class LifetimeEvictionPolicy implements EvictionPolicy<IMAPStore> {
        private final Duration idleEviction;
        private final Duration maxLifetime;

        LifetimeEvictionPolicy(Duration idleEviction, Duration maxLifetime) {
            this.idleEviction = idleEviction;
            this.maxLifetime = maxLifetime;
        }

        @Override
        public boolean evict(EvictionConfig config, PooledObject<IMAPStore> p, int idleCount) {
            return p.getIdleDuration().compareTo(idleEviction) > 0
                    || StorePoolFactory.age(p).compareTo(maxLifetime) > 0;
        }
    }

    public IMAPStore borrow(PoolKey key) throws Exception {
        return pool.borrowObject(key);
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
