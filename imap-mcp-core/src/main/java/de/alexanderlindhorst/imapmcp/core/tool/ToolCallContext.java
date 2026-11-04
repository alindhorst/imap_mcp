package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.MDC;

/**
 * Per-call context: identity, request id and a cancellation seam. The executor (deadline, virtual thread)
 * calls {@link #cancel()}; handlers register interrupt-style hooks via {@link #onCancel(Runnable)}.
 */
public final class ToolCallContext {

    private final AccountContext accountContext;
    private final String requestId;
    private final List<Runnable> callbacks = new ArrayList<>();
    private boolean cancelled;
    private volatile LogIds logIds;
    private volatile String boundAccountHash;
    private volatile Instant deadline;
    private volatile boolean mutationStarted;

    public ToolCallContext(AccountContext accountContext, String requestId) {
        this.accountContext = Objects.requireNonNull(accountContext, "accountContext");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
    }

    /** With a fixed deadline (tests, or callers that own the clock); the executor sets it otherwise. */
    public ToolCallContext(AccountContext accountContext, String requestId, Instant deadline) {
        this(accountContext, requestId);
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    /** The instant at which the executor gives up on this call; empty outside an executor. */
    public Optional<Instant> deadline() {
        return Optional.ofNullable(deadline);
    }

    /**
     * Write tools call this immediately before the first mutating IMAP command is written. From then on a
     * deadline, cancel or unchecked failure reports an unknown outcome (SPEC 5.5). Idempotent, thread-safe.
     */
    public void markMutationStarted() {
        mutationStarted = true;
    }

    public boolean mutationStarted() {
        return mutationStarted;
    }

    public AccountContext accountContext() {
        return accountContext;
    }

    public String requestId() {
        return requestId;
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Runs the hook on cancel, or immediately if already cancelled. */
    public void onCancel(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        synchronized (this) {
            if (!cancelled) {
                callbacks.add(callback);
                return;
            }
        }
        run(callback);
    }

    /** Idempotent; hooks run once on the calling thread, a failing hook does not stop the others. */
    public void cancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toRun = List.copyOf(callbacks);
            callbacks.clear();
        }
        toRun.forEach(ToolCallContext::run);
    }

    /** Sets the hashed account id in the worker's MDC once resolved; no-op outside the executor. */
    public void bindAccount(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        LogIds ids = logIds;
        if (ids != null) {
            String hash = ids.account(accountId.value());
            boundAccountHash = hash;
            MDC.put("acct", hash);
        }
    }

    /** The hashed account id bound by {@link #bindAccount}, or null; lets the executor restore the MDC elsewhere. */
    String boundAccountHash() {
        return boundAccountHash;
    }

    /** Called by the executor on the worker thread. */
    void attach(LogIds logIds, Instant executorDeadline) {
        this.logIds = logIds;
        if (deadline == null) {
            this.deadline = executorDeadline;
        }
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // a hook must never prevent cancellation of the remaining ones
        }
    }
}
