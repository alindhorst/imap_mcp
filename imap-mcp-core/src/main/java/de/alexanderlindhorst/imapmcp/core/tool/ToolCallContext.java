package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-call context: identity, request id and a cancellation seam. The executor (deadline, virtual thread)
 * calls {@link #cancel()}; handlers register interrupt-style hooks via {@link #onCancel(Runnable)}.
 */
public final class ToolCallContext {

    private final AccountContext accountContext;
    private final String requestId;
    private final List<Runnable> callbacks = new ArrayList<>();
    private boolean cancelled;

    public ToolCallContext(AccountContext accountContext, String requestId) {
        this.accountContext = Objects.requireNonNull(accountContext, "accountContext");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
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

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // a hook must never prevent cancellation of the remaining ones
        }
    }
}
