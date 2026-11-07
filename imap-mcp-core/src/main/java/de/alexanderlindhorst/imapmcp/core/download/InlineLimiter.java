package de.alexanderlindhorst.imapmcp.core.download;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.error.ServerBusyException;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concurrency caps of embedded downloads per replica (SPEC 4: {@code tools.download.inlineConcurrency} and, per
 * tenant, {@code inlineConcurrencyPerTenant}, D17). Both permits are taken without waiting, the tenant permit first,
 * so that one tenant cannot hold every replica permit; a tenant permit taken is given back when the replica permit
 * is missing. The per-tenant table has an entry only while a permit of that tenant is held (as {@code Admission},
 * SPEC 5.5), so it stays bounded by the replica cap.
 */
public final class InlineLimiter {

    public static final String SCOPE_TENANT = "download_tenant";
    public static final String SCOPE_REPLICA = "download_replica";

    /** Reports a refusal; {@code scope} is {@link #SCOPE_TENANT} or {@link #SCOPE_REPLICA}. */
    @FunctionalInterface
    public interface Listener {
        void rejected(PoolKey key, String scope);
    }

    /** Held for the duration of a download; {@link #close()} releases exactly once. */
    public final class Permit implements AutoCloseable {
        private final TenantId tenant;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Permit(TenantId tenant) {
            this.tenant = tenant;
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                replica.release();
                releaseTenant(tenant);
            }
        }
    }

    private final Semaphore replica;
    private final int perTenant;
    private final Listener listener;
    private final Map<TenantId, Integer> tenants = new ConcurrentHashMap<>();

    /** The per-tenant cap equals the replica cap (no extra restriction). */
    public InlineLimiter(int max) {
        this(max, max);
    }

    public InlineLimiter(int max, int perTenant) {
        this(max, perTenant, (key, scope) -> { });
    }

    /** {@code perTenant} is 1 to {@code max}; {@code listener} gets every refusal. */
    public InlineLimiter(int max, int perTenant, Listener listener) {
        if (max < 1) {
            throw new IllegalArgumentException("max");
        }
        if (perTenant < 1 || perTenant > max) {
            throw new IllegalArgumentException("perTenant");
        }
        this.replica = new Semaphore(max);
        this.perTenant = perTenant;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Non-blocking. The permit must be closed exactly once (idempotent).
     *
     * @throws ServerBusyException if the tenant's or the replica's cap is reached
     */
    public Permit tryAcquire(PoolKey key) {
        TenantId tenant = key.tenantId();
        if (!acquireTenant(tenant)) {
            refuse(key, SCOPE_TENANT);
        }
        if (!replica.tryAcquire()) {
            releaseTenant(tenant);
            refuse(key, SCOPE_REPLICA);
        }
        return new Permit(tenant);
    }

    private void refuse(PoolKey key, String scope) {
        try {
            listener.rejected(key, scope);
        } catch (RuntimeException ignored) {
            // a metrics failure never changes the refusal
        }
        throw new ServerBusyException();
    }

    private boolean acquireTenant(TenantId tenant) {
        boolean[] granted = new boolean[1];
        tenants.compute(tenant, (t, held) -> {
            int n = held == null ? 0 : held;
            granted[0] = n < perTenant;
            return granted[0] ? n + 1 : held; // a refused first permit leaves no entry
        });
        return granted[0];
    }

    private void releaseTenant(TenantId tenant) {
        tenants.computeIfPresent(tenant, (t, held) -> held <= 1 ? null : held - 1);
    }

    int tenantEntries() {
        return tenants.size();
    }
}
