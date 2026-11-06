package de.alexanderlindhorst.imapmcp.core.imap.pool;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.error.ServerBusyException;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fail-fast admission in front of the pool (SPEC 5.5): a global, a per-tenant and a per-mailbox semaphore, each
 * taken with {@code tryAcquire()} (never waiting) in the order global, tenant, mailbox and released in reverse.
 * Refusal throws {@link ServerBusyException} (SERVER_BUSY). Semaphores only, no thread pool.
 *
 * <p>The tenant and mailbox semaphores exist only while a permit is held: an entry is created by the first
 * acquire and removed by the release that frees its last permit (SPEC 5.5, bounded maps), both atomically per key.
 */
public final class Admission {

    public enum Scope { GLOBAL, TENANT, MAILBOX }

    /** Notified on every refusal (metrics hook, #20). Must not throw or block. */
    @FunctionalInterface
    public interface Listener {
        void rejected(PoolKey key, Scope scope);
    }

    /** Adapter that reports every refusal to the metrics recorder. */
    public static Listener recording(MetricsRecorder recorder) {
        Objects.requireNonNull(recorder, "recorder");
        return (key, scope) -> recorder.busyRejection(key, scope.name().toLowerCase(Locale.ROOT));
    }

    /** Held for the duration of a lease; {@link #close()} releases exactly once. */
    public final class Permit implements AutoCloseable {
        private final PoolKey key;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(PoolKey key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release(mailboxes, key, settings.maxPerMailbox());
                release(tenants, key.tenantId(), settings.maxPerTenant());
                global.release();
            }
        }
    }

    private final PoolSettings settings;
    private final Listener listener;
    private final Semaphore global;
    private final Map<TenantId, Semaphore> tenants = new ConcurrentHashMap<>();
    private final Map<PoolKey, Semaphore> mailboxes = new ConcurrentHashMap<>();

    public Admission(PoolSettings settings) {
        this(settings, (key, scope) -> { });
    }

    public Admission(PoolSettings settings, Listener listener) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.global = new Semaphore(settings.maxTotal());
    }

    /** @throws ServerBusyException if any scope is exhausted; nothing stays acquired in that case */
    public Permit tryAcquire(PoolKey key) {
        Objects.requireNonNull(key, "key");
        if (!global.tryAcquire()) {
            throw busy(key, Scope.GLOBAL);
        }
        if (!acquire(tenants, key.tenantId(), settings.maxPerTenant())) {
            global.release();
            throw busy(key, Scope.TENANT);
        }
        if (!acquire(mailboxes, key, settings.maxPerMailbox())) {
            release(tenants, key.tenantId(), settings.maxPerTenant());
            global.release();
            throw busy(key, Scope.MAILBOX);
        }
        return new Permit(key);
    }

    /** Takes one permit of the key's semaphore, creating it if needed; an entry nobody holds is not kept. */
    private static <K> boolean acquire(Map<K, Semaphore> map, K key, int max) {
        boolean[] taken = new boolean[1];
        map.compute(key, (k, s) -> {
            Semaphore sem = s == null ? new Semaphore(max) : s;
            taken[0] = sem.tryAcquire();
            return sem.availablePermits() >= max ? null : sem;
        });
        return taken[0];
    }

    /** Returns one permit and removes the entry once all permits are back. */
    private static <K> void release(Map<K, Semaphore> map, K key, int max) {
        map.computeIfPresent(key, (k, sem) -> {
            sem.release();
            return sem.availablePermits() >= max ? null : sem;
        });
    }

    int tenantEntries() {
        return tenants.size();
    }

    int mailboxEntries() {
        return mailboxes.size();
    }

    private ServerBusyException busy(PoolKey key, Scope scope) {
        try {
            listener.rejected(key, scope);
        } catch (RuntimeException ignored) {
            // metrics must never change admission behaviour
        }
        return new ServerBusyException();
    }

    public int availableGlobal() {
        return global.availablePermits();
    }

    public int availableTenant(TenantId tenant) {
        Semaphore s = tenants.get(Objects.requireNonNull(tenant, "tenant"));
        return s == null ? settings.maxPerTenant() : s.availablePermits();
    }

    public int availableMailbox(PoolKey key) {
        Semaphore s = mailboxes.get(Objects.requireNonNull(key, "key"));
        return s == null ? settings.maxPerMailbox() : s.availablePermits();
    }
}
