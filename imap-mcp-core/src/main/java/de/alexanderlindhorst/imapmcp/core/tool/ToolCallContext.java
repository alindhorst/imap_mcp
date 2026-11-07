package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.audit.AuditLog;
import de.alexanderlindhorst.imapmcp.core.audit.CallAudit;
import de.alexanderlindhorst.imapmcp.core.audit.CallInfo;
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
    private final List<Runnable> mutationHooks = new ArrayList<>();
    private boolean cancelled;
    private volatile LogIds logIds;
    private volatile String boundAccountHash;
    private volatile Instant deadline;
    private boolean sealed; // guarded by this, like cancelled
    private volatile boolean mutationStarted; // written under this lock only
    private volatile boolean authFailureCounted;
    private volatile boolean argumentsRejected;
    private volatile ElicitationChannel elicitation;
    private volatile AccountId boundAccountId;
    private volatile CallAudit audit = CallAudit.disabled();
    private volatile String linkUrl;

    public ToolCallContext(AccountContext accountContext, String requestId) {
        this.accountContext = Objects.requireNonNull(accountContext, "accountContext");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
    }

    /** With a fixed deadline (tests, or callers that own the clock); the executor sets it otherwise. */
    public ToolCallContext(AccountContext accountContext, String requestId, Instant deadline) {
        this(accountContext, requestId);
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    /**
     * Turns on the multi-tenant audit log for this call (SPEC 5.3); the executor does it for write tools when the
     * deployment has an audit log. Without it {@link #audit()} does nothing.
     */
    public void attachAudit(AuditLog log, String tool) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(tool, "tool");
        this.audit = new CallAudit(log, () -> new CallInfo(accountContext.tenantId(), boundAccountId, requestId, tool));
    }

    /**
     * The call fails check-order step 1 (schema and code checks, SPEC 4; D10): the executor counts it as
     * {@code write_argument_rejected} and the audit writes no row. Set before returning the INVALID_ARGUMENT.
     */
    public void rejectArguments() {
        argumentsRejected = true;
        audit.argumentsRejected(true);
    }

    public boolean argumentsRejected() {
        return argumentsRejected;
    }

    /** The call's audit record; a no-op object when no audit log is attached. */
    public CallAudit audit() {
        return audit;
    }

    /** The linking surface's page for re-link errors, set by the account resolution (http-public). */
    public void bindLinkUrl(String linkUrl) {
        this.linkUrl = linkUrl;
    }

    /** The bound link URL, or null. */
    public String linkUrl() {
        return linkUrl;
    }

    /** Set once by the stateful adapter before the handler runs; the stateless one leaves it empty. */
    public void bindElicitation(ElicitationChannel channel) {
        this.elicitation = Objects.requireNonNull(channel, "channel");
    }

    /** The session's way to ask the user, or empty when the transport cannot (SPEC 5.5). */
    public Optional<ElicitationChannel> elicitation() {
        return Optional.ofNullable(elicitation);
    }

    /** The instant at which the executor gives up on this call; empty outside an executor. */
    public Optional<Instant> deadline() {
        return Optional.ofNullable(deadline);
    }

    /**
     * Write tools call this immediately before each mutating IMAP command is written and write only if it returns
     * true. It sets the mutation mark atomically under the lock that {@link #cancel()} and the executor's decision use
     * (SPEC 5.5): it returns false once the call is cancelled or its outcome has been decided, so no mutation starts
     * after the client was told that nothing happened. A caller that gets false writes nothing and unwinds. After the
     * first true, a deadline, cancel or unchecked failure reports an unknown outcome. Never held across I/O.
     */
    public boolean tryBeginMutation() {
        List<Runnable> hooks;
        synchronized (this) {
            if (cancelled || sealed) {
                return false;
            }
            boolean first = !mutationStarted;
            mutationStarted = true;
            hooks = first ? List.copyOf(mutationHooks) : List.of();
            mutationHooks.clear();
        }
        hooks.forEach(ToolCallContext::run); // outside the lock; a failing hook does not stop the others
        return true;
    }

    /**
     * Runs {@code hook} once, on the thread of the first {@link #tryBeginMutation()} that returns true, right after
     * the mark is set; never if the call is cancelled or decided first. For effects that may exist only once the
     * deletion has really begun (a purge session approval, SPEC 4).
     */
    public synchronized void onMutationBegun(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        if (!mutationStarted) {
            mutationHooks.add(hook);
        } else {
            run(hook);
        }
    }

    /**
     * The executor calls this when it decides the outcome of a deadline, cancel or failure: from now on
     * {@link #tryBeginMutation()} returns false, so the returned flag (a mutation began) cannot change anymore.
     */
    synchronized boolean sealMutation() {
        sealed = true;
        return mutationStarted;
    }

    /**
     * The lease layer counted a refused login of this call in the metrics (SPEC 5.6); the executor then does not count
     * the resulting AUTH_FAILED outcome a second time.
     */
    public void markAuthFailureCounted() {
        authFailureCounted = true;
    }

    boolean authFailureCounted() {
        return authFailureCounted;
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
        boundAccountId = accountId;
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
