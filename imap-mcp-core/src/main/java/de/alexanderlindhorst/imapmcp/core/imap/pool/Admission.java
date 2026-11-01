package de.alexanderlindhorst.imapmcp.core.imap.pool;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.error.ServerBusyException;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
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
 * <p>The tenant and mailbox semaphores are created on demand and never removed: acceptable in v1 where the set
 * of accounts is fixed and small.
 */
public final class Admission {

    public enum Scope { GLOBAL, TENANT, MAILBOX }

    /** Notified on every refusal (metrics hook, #20). Must not throw or block. */
    @FunctionalInterface
    public interface Listener {
        void rejected(Scope scope);
    }

    /** Held for the duration of a lease; {@link #close()} releases exactly once. */
    public final class Permit implements AutoCloseable {
        private final Semaphore global;
        private final Semaphore tenant;
        private final Semaphore mailbox;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore global, Semaphore tenant, Semaphore mailbox) {
            this.global = global;
            this.tenant = tenant;
            this.mailbox = mailbox;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                mailbox.release();
                tenant.release();
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
        this(settings, scope -> { });
    }

    public Admission(PoolSettings settings, Listener listener) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.global = new Semaphore(settings.maxTotal());
    }

    /** @throws ServerBusyException if any scope is exhausted; nothing stays acquired in that case */
    public Permit tryAcquire(PoolKey key) {
        Objects.requireNonNull(key, "key");
        Semaphore tenant = tenants.computeIfAbsent(key.tenantId(), k -> new Semaphore(settings.maxPerTenant()));
        Semaphore mailbox = mailboxes.computeIfAbsent(key, k -> new Semaphore(settings.maxPerMailbox()));
        if (!global.tryAcquire()) {
            throw busy(Scope.GLOBAL);
        }
        if (!tenant.tryAcquire()) {
            global.release();
            throw busy(Scope.TENANT);
        }
        if (!mailbox.tryAcquire()) {
            tenant.release();
            global.release();
            throw busy(Scope.MAILBOX);
        }
        return new Permit(global, tenant, mailbox);
    }

    private ServerBusyException busy(Scope scope) {
        try {
            listener.rejected(scope);
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
