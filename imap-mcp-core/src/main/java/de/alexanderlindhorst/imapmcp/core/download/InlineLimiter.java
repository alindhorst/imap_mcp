package de.alexanderlindhorst.imapmcp.core.download;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.error.ServerBusyException;
import java.util.concurrent.Semaphore;

/** At most {@code max} embedded downloads at once per replica (SPEC 4 {@code tools.download.inlineConcurrency}). */
public final class InlineLimiter {

    /** Reports a refusal; {@code scope} is {@code download_tenant} or {@code download_replica}. */
    @FunctionalInterface
    public interface Listener {
        void rejected(PoolKey key, String scope);
    }

    /** Held for the duration of a download; {@link #close()} releases exactly once. */
    public final class Permit implements AutoCloseable {
        @Override
        public void close() {
        }
    }

    private final Semaphore permits;

    public InlineLimiter(int max) {
        this(max, max);
    }

    public InlineLimiter(int max, int perTenant) {
        this(max, perTenant, (key, scope) -> { });
    }

    public InlineLimiter(int max, int perTenant, Listener listener) {
        if (max < 1) {
            throw new IllegalArgumentException("max");
        }
        this.permits = new Semaphore(max);
    }

    /** Stub (red commit). */
    public Permit tryAcquire(PoolKey key) {
        if (!permits.tryAcquire()) {
            throw new ServerBusyException();
        }
        return new Permit();
    }

    int tenantEntries() {
        return 0;
    }

    /** Non-blocking. A true result must be followed by exactly one {@link #release()}. */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }
}
