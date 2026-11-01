package de.alexanderlindhorst.imapmcp.core.imap.pool;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import java.net.ConnectException;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for tools: leases an exclusive {@link IMAPStore} for one tool call (SPEC 5.5). The whole borrow,
 * including connect, TLS and LOGIN, is bounded by the acquire timeout: it runs on its own virtual thread and the
 * caller waits with a timeout (the pool's {@code maxWait} only covers waiting for a free slot). Admission control
 * is applied here, before any I/O.
 */
public final class StoreLeases {

    private static final Logger LOG = LoggerFactory.getLogger(StoreLeases.class);

    private final StorePool pool;
    private final Duration acquireTimeout;
    private final Admission admission;

    public StoreLeases(StorePool pool, Duration acquireTimeout) {
        this(pool, acquireTimeout, null);
    }

    /** {@code admission} may be null (no limiting, tests only); production wiring always passes one. */
    public StoreLeases(StorePool pool, Duration acquireTimeout, Admission admission) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        this.admission = admission;
    }

    /**
     * @throws StoreUnavailableException on connect failure, acquire timeout or cancellation; nothing is leaked
     *         in any of these cases, a Store that finishes connecting late is invalidated
     */
    public ImapLease lease(PoolKey key, ToolCallContext ctx) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ctx, "ctx");
        // Fail fast before any I/O; the permit is handed to the lease, which releases it exactly once.
        Admission.Permit permit = admission == null ? null : admission.tryAcquire(key);
        try {
            return borrow(key, ctx, permit);
        } catch (Throwable t) {
            if (permit != null) {
                permit.close();
            }
            throw t;
        }
    }

    private ImapLease borrow(PoolKey key, ToolCallContext ctx, Admission.Permit permit) {
        if (ctx.isCancelled()) {
            throw new StoreUnavailableException(new InterruptedException("cancelled"));
        }
        CompletableFuture<IMAPStore> result = new CompletableFuture<>();
        Thread borrower = Thread.ofVirtual().name("imap-borrow").unstarted(() -> {
            try {
                IMAPStore store = pool.borrow(key);
                if (!result.complete(store)) {
                    discard(key, store); // caller gave up while we were connecting
                }
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        ctx.onCancel(borrower::interrupt);
        borrower.start();
        IMAPStore store;
        try {
            store = result.get(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            store = abandon(result, borrower, key, new ConnectException("acquire timeout"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store = abandon(result, borrower, key, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new StoreUnavailableException(
                    cause instanceof NoSuchElementException ? new ConnectException("pool exhausted") : cause);
        }
        if (ctx.isCancelled()) {
            discard(key, store);
            throw new StoreUnavailableException(new InterruptedException("cancelled"));
        }
        return new ImapLease(pool, key, store, ctx, permit);
    }

    /** Marks the borrow as abandoned; returns the Store if it arrived in the meantime, else throws. */
    private IMAPStore abandon(CompletableFuture<IMAPStore> result, Thread borrower, PoolKey key, Throwable why) {
        borrower.interrupt();
        if (result.completeExceptionally(why)) {
            throw new StoreUnavailableException(why);
        }
        return result.join(); // completed normally just before; cannot fail
    }

    private void discard(PoolKey key, IMAPStore store) {
        try {
            pool.invalidate(key, store);
        } catch (Exception e) {
            LOG.debug("invalidating abandoned store failed: {}", e.getClass().getSimpleName());
        }
    }
}
