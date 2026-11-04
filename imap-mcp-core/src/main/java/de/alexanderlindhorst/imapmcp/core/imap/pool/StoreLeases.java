package de.alexanderlindhorst.imapmcp.core.imap.pool;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.net.ConnectException;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import java.time.Duration;
import java.time.Instant;
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
    private final MetricsRecorder metrics;

    public StoreLeases(StorePool pool, Duration acquireTimeout) {
        this(pool, acquireTimeout, null);
    }

    /** {@code admission} may be null (no limiting, tests only); production wiring always passes one. */
    public StoreLeases(StorePool pool, Duration acquireTimeout, Admission admission) {
        this(pool, acquireTimeout, admission, MetricsRecorder.NOOP);
    }

    /** {@code metrics} receives the wait of every successful lease (borrow incl. connect). */
    public StoreLeases(StorePool pool, Duration acquireTimeout, Admission admission, MetricsRecorder metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        this.admission = admission;
    }

    /** Work executed on a lease; a tool catches and maps the {@link MessagingException} itself, or the Store is discarded. */
    @FunctionalInterface
    public interface LeaseWork<T> {
        T apply(ImapLease lease) throws MessagingException;
    }

    /**
     * The only way for tools to obtain a lease. Runs {@code work} on a lease; any {@link Throwable} escaping the
     * work, checked {@link MessagingException} included, marks the lease broken (the Store is invalidated) and is
     * rethrown unchanged. A tool that wants the Store back after a failure that is not a connection failure (NO/BAD)
     * must catch the exception inside the work, map it with {@code ImapErrorMapper} and call
     * {@link ImapLease#markBroken()} only when {@code discardConnection} is set.
     *
     * @throws StoreUnavailableException see {@link #lease}
     */
    public <T> T withLease(PoolKey key, ToolCallContext ctx, LeaseWork<T> work) throws MessagingException {
        Objects.requireNonNull(work, "work");
        try (ImapLease lease = lease(key, ctx)) {
            Instant start = metrics.now();
            try {
                return ImapLease.runBound(lease, () -> work.apply(lease));
            } catch (Throwable e) {
                lease.markBroken();
                throw e;
            } finally {
                if (!lease.wrote()) {
                    lease.record("read", start); // one bucket per read lease operation (SPEC 5.6)
                }
            }
        }
    }

    /**
     * @throws StoreUnavailableException on connect failure, acquire timeout or cancellation; nothing is leaked
     *         in any of these cases, a Store that finishes connecting late is invalidated
     */
    ImapLease lease(PoolKey key, ToolCallContext ctx) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ctx, "ctx");
        // Fail fast before any I/O; the permit is handed to the lease, which releases it exactly once.
        Admission.Permit permit = admission == null ? null : admission.tryAcquire(key);
        try {
            Instant start = metrics.now();
            ImapLease lease = borrow(key, ctx, permit);
            metrics.leaseAcquired(key, Duration.between(start, metrics.now()));
            return lease;
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
            if (isAuthFailure(cause)) { // once per failed connect, whichever caller (tool call or probe) triggered it
                metrics.authFailure(key.tenantId());
                ctx.markAuthFailureCounted();
            }
            throw new StoreUnavailableException(
                    cause instanceof NoSuchElementException ? new ConnectException("pool exhausted") : cause);
        }
        if (ctx.isCancelled()) {
            discard(key, store);
            throw new StoreUnavailableException(new InterruptedException("cancelled"));
        }
        return new ImapLease(pool, key, store, ctx, permit, metrics);
    }

    private static boolean isAuthFailure(Throwable t) {
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof AuthenticationFailedException) {
                return true;
            }
        }
        return false;
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
