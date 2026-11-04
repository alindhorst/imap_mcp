package de.alexanderlindhorst.imapmcp.core.imap.pool;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.MessagingException;
import jakarta.mail.ReadOnlyFolderException;
import jakarta.mail.StoreClosedException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.iap.ResponseHandler;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exclusive lease of one {@link IMAPStore} for one tool call (SPEC 5.4, 5.5). Used by the worker thread of the
 * call only. At most one folder is open at a time, READ_ONLY (EXAMINE) for read tools or READ_WRITE (SELECT)
 * for write tools, and always closed with {@code close(false)}. Store-level commands (LIST/STATUS) use {@link #store()}, which refuses while a folder is
 * open so the lease never needs a second socket. {@code close()} returns the Store to the pool only after a clean
 * run; any failure seen by the lease, {@link #markBroken()}, cancellation or interrupt invalidates it instead.
 * No {@code Message} objects may outlive the lease.
 */
public final class ImapLease implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ImapLease.class);

    private final StorePool pool;
    private final PoolKey key;
    private final IMAPStore store;
    private final ToolCallContext ctx;
    private final AtomicBoolean broken = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Admission.Permit permit; // nullable
    private final MetricsRecorder metrics;
    private volatile boolean wrote; // a READ_WRITE folder was opened or a write command timed: not a "read" lease
    private IMAPFolder folder; // worker thread only
    // Set by the BYE watch (synchronously on the thread that reads the response, before close(false) returns).
    private final AtomicBoolean connectionLost = new AtomicBoolean();
    private final ResponseHandler byeWatch = new ByeWatch();
    private IMAPProtocol watched; // protocol carrying byeWatch; worker thread only
    private WriteCapabilities forcedCapabilities; // test seam, package-private setter only

    /** Tool name under which lease-level IMAP latencies are recorded (SPEC 5.6). */
    public static final String METRIC_TOOL = "imap";

    ImapLease(StorePool pool, PoolKey key, IMAPStore store, ToolCallContext ctx, Admission.Permit permit) {
        this(pool, key, store, ctx, permit, MetricsRecorder.NOOP);
    }

    ImapLease(StorePool pool, PoolKey key, IMAPStore store, ToolCallContext ctx, Admission.Permit permit,
            MetricsRecorder metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.pool = pool;
        this.key = key;
        this.store = Objects.requireNonNull(store, "store");
        this.ctx = ctx;
        this.permit = permit;
        // Runs on the cancelling thread (deadline path): the Store is invalidated without waiting for the worker.
        // Deliberate: this releases the admission permit and invalidates the Store BEFORE the interrupted worker has
        // unwound, so the socket and semaphore caps can be exceeded briefly (until the interrupt closes the socket).
        // Deferring the permit to the worker's unwind could leak it forever if the worker never unwinds.
        // Note: store.close() alone does NOT unblock a worker stuck in a socket read (LOGOUT blocks as well);
        // what unblocks it is the executor's interrupt of the virtual worker thread, which closes the socket.
        // The hook must not block: pool invalidation can run a waiting borrower's connect synchronously
        // (commons-pool2 reuseCapacity), so only the CAS and the permit are handled here, the invalidation
        // runs on its own virtual thread.
        ctx.onCancel(() -> {
            broken.set(true);
            finish(true, true);
        });
    }

    /** Opens the folder READ_ONLY; a previously opened folder is closed first. */
    public IMAPFolder openReadOnly(String folderFullName) throws MessagingException {
        closeFolder();
        ensureUsable();
        ensureConnected();
        Instant start = metrics.now();
        try {
            IMAPFolder f = (IMAPFolder) store.getFolder(folderFullName);
            f.open(Folder.READ_ONLY);
            record("open", start);
            folder = f;
            watchForBye(f);
            return f;
        } catch (MessagingException | RuntimeException e) {
            markBroken();
            throw e;
        }
    }

    /**
     * Opens the folder READ_WRITE (SELECT) for write tools; a previously opened folder is closed first. A connection-level
     * failure marks the lease broken; a NO / {@code [NOPERM]} or a {@code [READ-ONLY]} answer is rethrown with the
     * lease intact, so the caller can go on with its next folder group (SPEC 5.4).
     */
    public IMAPFolder openReadWrite(String folderFullName) throws MessagingException {
        closeFolder();
        ensureUsable();
        ensureConnected();
        wrote = true;
        Instant start = metrics.now();
        try {
            IMAPFolder f = (IMAPFolder) store.getFolder(folderFullName);
            f.open(Folder.READ_WRITE);
            record("open", start);
            folder = f;
            watchForBye(f);
            return f;
        } catch (MessagingException | RuntimeException e) {
            if (!isCommandLevelRefusal(e)) {
                markBroken();
            }
            throw e;
        }
    }

    /**
     * Angus' {@code close(false)} (and the {@code isOpen()} NOOP) swallow a connection loss during UNSELECT / CLOSE, so the
     * lease would pool a dead Store. A handler on the folder's protocol sees every BYE, synthetic or from the server.
     * {@code doCommand} here only hands over the protocol; nothing is sent.
     */
    private void watchForBye(IMAPFolder f) throws MessagingException {
        IMAPProtocol p = f.doCommand(proto -> {
            proto.addResponseHandler(byeWatch);
            return proto;
        }) instanceof IMAPProtocol ip ? ip : null;
        watched = p;
    }

    /** SPEC 5.5: after a lost connection later groups are not attempted; no transparent reconnect inside one lease. */
    private void ensureConnected() throws StoreClosedException {
        if (connectionLost.get()) {
            throw new StoreClosedException(store, "connection lost");
        }
    }

    /** Flags the lease on any BYE; sends no I/O, takes no locks, never looks at or logs the response text. */
    private final class ByeWatch implements ResponseHandler {
        @Override
        public void handleResponse(Response r) {
            if (r != null && r.isBYE()) {
                connectionLost.set(true);
                broken.set(true);
            }
        }
    }

    /** True for an answer of the server to the SELECT itself (NO, [READ-ONLY]); the connection is still sound. */
    private static boolean isCommandLevelRefusal(Exception e) {
        if (e instanceof FolderClosedException || e instanceof StoreClosedException) {
            return false;
        }
        if (e instanceof ReadOnlyFolderException) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return false;
            }
            if (t instanceof CommandFailedException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Closes the open folder with {@code close(false)}; a failure marks the lease broken. */
    public void closeFolder() {
        IMAPFolder f = folder;
        folder = null;
        if (f == null) {
            return;
        }
        try {
            f.close(false); // no isOpen() pre-check: its NOOP would absorb a dead connection
        } catch (IllegalStateException e) {
            if (!connectionLost.get()) { // a BYE already cleaned the folder up; otherwise a genuine failure
                LOG.debug("closing folder failed: {}", e.getClass().getSimpleName());
                markBroken();
            }
        } catch (MessagingException | RuntimeException e) {
            LOG.debug("closing folder failed: {}", e.getClass().getSimpleName());
            markBroken();
        } finally {
            // The protocol is pooled with the Store and reused by the next lease: the handler must not leak.
            IMAPProtocol p = watched;
            watched = null;
            if (p != null) {
                p.removeResponseHandler(byeWatch);
            }
        }
    }

    /** The Store for store-level commands; only while no folder is open (one socket per lease). */
    public IMAPStore store() {
        if (folder != null) {
            throw new IllegalStateException("a folder is open; close it before store-level commands");
        }
        ensureUsable();
        return store;
    }

    /**
     * The leased connection's write capabilities (no I/O, never the CapabilityCache); see {@link WriteCapabilities}.
     * Only while no folder is open: with a folder open the Store would open a second connection.
     */
    public WriteCapabilities capabilities() throws MessagingException {
        ensureUsable();
        return forcedCapabilities != null ? forcedCapabilities : WriteCapabilities.from(store);
    }

    /** Test seam: forces what {@link #capabilities()} reports. Not reachable from production wiring. */
    void overrideCapabilities(WriteCapabilities forced) {
        this.forcedCapabilities = forced;
    }

    /** Records one IMAP operation of this lease under the hashed tenant of its key; never throws. */
    void record(String op, Instant start) {
        metrics.operation(key.tenantId(), METRIC_TOOL, op, Duration.between(start, metrics.now()));
    }

    /** True once the lease opened a READ_WRITE folder; such a lease is not counted in the "read" bucket. */
    boolean wrote() {
        return wrote;
    }

    /** The lease must not return its Store to the pool (error, timeout, discard-worthy condition). */
    public void markBroken() {
        broken.set(true);
    }

    @Override
    public void close() {
        closeFolder();
        boolean discard = broken.get() || ctx.isCancelled() || Thread.currentThread().isInterrupted();
        finish(discard, false);
    }

    private void ensureUsable() {
        if (finished.get()) {
            throw new IllegalStateException("lease is closed or was cancelled");
        }
    }

    private void finish(boolean discard, boolean asyncInvalidate) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        try {
            if (discard && asyncInvalidate) {
                Thread.ofVirtual().name("imap-lease-invalidate").start(this::invalidateQuietly);
            } else if (discard) {
                pool.invalidate(key, store);
            } else {
                pool.release(key, store);
            }
        } catch (Exception e) {
            LOG.debug("releasing store failed: {}", e.getClass().getSimpleName());
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    private void invalidateQuietly() {
        try {
            pool.invalidate(key, store);
        } catch (Exception e) {
            LOG.debug("invalidating store failed: {}", e.getClass().getSimpleName());
        }
    }
}
