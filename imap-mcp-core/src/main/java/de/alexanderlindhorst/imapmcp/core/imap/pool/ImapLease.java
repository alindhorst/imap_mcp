package de.alexanderlindhorst.imapmcp.core.imap.pool;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.MessagingException;
import jakarta.mail.ReadOnlyFolderException;
import jakarta.mail.StoreClosedException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
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
    private IMAPFolder folder; // worker thread only
    private WriteCapabilities forcedCapabilities; // test seam, package-private setter only

    ImapLease(StorePool pool, PoolKey key, IMAPStore store, ToolCallContext ctx, Admission.Permit permit) {
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
        try {
            IMAPFolder f = (IMAPFolder) store.getFolder(folderFullName);
            f.open(Folder.READ_ONLY);
            folder = f;
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
        try {
            IMAPFolder f = (IMAPFolder) store.getFolder(folderFullName);
            f.open(Folder.READ_WRITE);
            folder = f;
            return f;
        } catch (MessagingException | RuntimeException e) {
            if (!isCommandLevelRefusal(e)) {
                markBroken();
            }
            throw e;
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
            if (f.isOpen()) {
                f.close(false);
            }
        } catch (MessagingException | RuntimeException e) {
            LOG.debug("closing folder failed: {}", e.getClass().getSimpleName());
            markBroken();
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
     * Usable while a folder is open or not.
     */
    public WriteCapabilities capabilities() throws MessagingException {
        ensureUsable();
        return forcedCapabilities != null ? forcedCapabilities : WriteCapabilities.from(store);
    }

    /** Test seam: forces what {@link #capabilities()} reports. Not reachable from production wiring. */
    void overrideCapabilities(WriteCapabilities forced) {
        this.forcedCapabilities = forced;
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
