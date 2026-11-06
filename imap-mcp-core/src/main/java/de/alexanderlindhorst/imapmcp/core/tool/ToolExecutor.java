package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.net.AddressBlockedException;
import de.alexanderlindhorst.imapmcp.core.net.RefusalMarker;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Runs every tool call on its own virtual thread with an overall deadline (SPEC 5.5). The calling SDK thread
 * waits for the result; on the deadline the context is cancelled (hooks run once, e.g. the lease discards its
 * Store), the worker is interrupted and TIMEOUT is returned without waiting for the worker. An external
 * {@link ToolCallContext#cancel()} ends the call the same way (TIMEOUT, no extra code): the first completion of
 * the result future wins, so a result the handler produces after the cancel is never returned. MDC (req, tool,
 * tenant hashed, acct hashed once bound) lives on the worker only and is cleared there.
 *
 * <p>Once a write tool marked {@link ToolCallContext#tryBeginMutation()}, a deadline, cancel or unchecked
 * failure reports an unknown outcome instead (details.outcome, or OUTCOME_UNKNOWN for non-idempotent tools).
 *
 * <p>An optional {@link OutcomeListener} is told about every outcome of a write tool (readOnlyHint=false) that the
 * executor produced itself (deadline, cancel, unchecked failure), so the caller can log it; outcomes the handler
 * returns are never reported. This keeps the write-call logging out of this package.
 *
 * <p>The request id is created by the adapter and carried in the {@link ToolCallContext}.
 */
public final class ToolExecutor {

    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(90);

    private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

    private final Duration deadline;
    private final LogIds logIds;
    private final MetricsRecorder metrics;
    private final OutcomeListener listener;

    /** Told about an executor-produced failure of a write tool; {@code code} is the final code on the wire. */
    @FunctionalInterface
    public interface OutcomeListener {
        OutcomeListener NONE = (tool, requestId, code, duration) -> { };

        void executorOutcome(String tool, String requestId, ErrorCode code, Duration duration);
    }

    /** Per-call flags: an external cancel (not a timeout), and an outcome produced here instead of by the handler. */
    private static final class CallState {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean executorProduced = new AtomicBoolean();
    }

    public ToolExecutor(Duration deadline, LogIds logIds) {
        this(deadline, logIds, MetricsRecorder.NOOP);
    }

    /**
     * {@code metrics} gets the per-tool call latency (op "call") and, per tenant from the AccountContext, every
     * AUTH_FAILED and TIMEOUT outcome. The pool key is not known here, so these are tenant-level counters.
     */
    public ToolExecutor(Duration deadline, LogIds logIds, MetricsRecorder metrics) {
        this(deadline, logIds, metrics, OutcomeListener.NONE);
    }

    public ToolExecutor(Duration deadline, LogIds logIds, MetricsRecorder metrics, OutcomeListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.logIds = Objects.requireNonNull(logIds, "logIds");
    }

    /** For handlers that never mutate (they never mark, so outcomes are the v1 ones). */
    public ToolHandler wrap(String toolName, ToolHandler delegate) {
        return wrap(toolName, true, false, delegate);
    }

    /** The tool's idempotentHint selects the outcome-unknown code after a mutation started (SPEC 5.5). */
    public ToolHandler wrap(ToolDefinition definition, ToolHandler delegate) {
        Objects.requireNonNull(definition, "definition");
        return wrap(definition.name(), definition.hints().idempotent(), !definition.hints().readOnly(), delegate);
    }

    private ToolHandler wrap(String toolName, boolean idempotent, boolean write, ToolHandler delegate) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(delegate, "delegate");
        return (ctx, args) -> execute(toolName, idempotent, write, delegate, ctx, args);
    }

    /** A write may have been applied: OUTCOME_UNKNOWN for a non-idempotent tool, else the error plus details.outcome. */
    private static ToolError afterMutation(String tool, ToolError error, boolean idempotent, ToolCallContext ctx) {
        if (!ctx.mutationStarted()) { // sealed by the callers below: no mutation can begin after this read
            return error;
        }
        switch (error.code()) {
            case TIMEOUT, UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> {
                return idempotent ? error.withOutcomeUnknown() : ToolError.outcomeUnknown(tool, ctx.requestId());
            }
            default -> {
                return error;
            }
        }
    }

    private ToolOutcome execute(String toolName, boolean idempotent, boolean write, ToolHandler delegate,
            ToolCallContext ctx, Map<String, Object> args) {
        Instant start = metrics.now();
        CallState state = new CallState();
        ToolOutcome outcome = run(toolName, idempotent, delegate, ctx, args, state);
        TenantId tenant = ctx.accountContext().tenantId();
        Duration elapsed = Duration.between(start, metrics.now());
        metrics.operation(tenant, toolName, "call", elapsed);
        if (write && state.executorProduced.get() && outcome instanceof ToolOutcome.Failure f) {
            reportExecutorOutcome(toolName, ctx, f.error().code(), elapsed);
        }
        if (outcome instanceof ToolOutcome.Failure f) {
            switch (f.error().code()) {
                case AUTH_FAILED -> {
                    if (!ctx.authFailureCounted()) { // a refused connect is already counted by the lease layer
                        metrics.authFailure(tenant);
                    }
                }
                case TIMEOUT -> {
                    if (!state.cancelled.get()) { // an external cancel is not a timeout
                        metrics.timeout(tenant);
                    }
                }
                default -> { }
            }
        }
        return outcome;
    }

    /** The listener runs on the caller thread: bind the keys the worker had (req, tool, hashed tenant and account). */
    private void reportExecutorOutcome(String toolName, ToolCallContext ctx, ErrorCode code, Duration elapsed) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("req", ctx.requestId());
            MDC.put("tool", toolName);
            MDC.put("tenant", logIds.tenant(ctx.accountContext().tenantId().value()));
            String acct = ctx.boundAccountHash();
            if (acct != null) {
                MDC.put("acct", acct);
            }
            listener.executorOutcome(toolName, ctx.requestId(), code, elapsed);
        } finally {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    private ToolOutcome run(String toolName, boolean idempotent, ToolHandler delegate, ToolCallContext ctx, Map<String, Object> args,
            CallState state) {
        Instant callDeadline = Instant.now().plus(deadline);
        CompletableFuture<ToolOutcome> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("tool-" + toolName).unstarted(() -> {
            try {
                MDC.put("req", ctx.requestId());
                MDC.put("tool", toolName);
                MDC.put("tenant", logIds.tenant(ctx.accountContext().tenantId().value()));
                ctx.attach(logIds, callDeadline);
                future.complete(delegate.handle(ctx, args));
            } catch (Throwable t) {
                // the marker is per thread; carry it over to the mapping thread, only for Angus' bare failure
                if (RefusalMarker.takeRecent() && ImapErrorMapper.hasBareConnectionFailure(t)) {
                    t.addSuppressed(new AddressBlockedException());
                }
                future.completeExceptionally(t);
            } finally {
                MDC.clear();
            }
        });
        // First hook: completes the result before the interrupt, so nothing the worker produces afterwards is returned.
        ctx.onCancel(() -> {
            if (future.complete(timedOut(toolName, idempotent, ctx))) {
                state.executorProduced.set(true);
                state.cancelled.set(true);
                LOG.info("tool {} cancelled, request_id={}", toolName, ctx.requestId());
            }
        });
        ctx.onCancel(worker::interrupt);
        if (future.isDone()) { // cancelled before start: no worker, no IMAP I/O
            return result(future, toolName, idempotent, ctx, state);
        }
        worker.start();
        try {
            return future.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (future.complete(timedOut(toolName, idempotent, ctx))) {
                state.executorProduced.set(true);
                LOG.warn("tool {} exceeded deadline, request_id={}", toolName, ctx.requestId());
            }
            ctx.cancel();
            return result(future, toolName, idempotent, ctx, state);
        } catch (InterruptedException e) {
            if (future.complete(timedOut(toolName, idempotent, ctx))) {
                state.executorProduced.set(true);
            }
            ctx.cancel();
            Thread.currentThread().interrupt();
            return result(future, toolName, idempotent, ctx, state);
        } catch (ExecutionException e) {
            return failed(e.getCause(), toolName, idempotent, ctx, state);
        }
    }

    /** The already completed future's outcome; the first completion (handler, deadline or cancel) won. */
    private ToolOutcome result(CompletableFuture<ToolOutcome> future, String toolName, boolean idempotent,
            ToolCallContext ctx, CallState state) {
        try {
            return future.join();
        } catch (CompletionException e) {
            return failed(e.getCause(), toolName, idempotent, ctx, state);
        }
    }

    private ToolOutcome failed(Throwable cause, String toolName, boolean idempotent, ToolCallContext ctx,
            CallState state) {
        state.executorProduced.set(true);
        ctx.sealMutation();
        // Class only: exception messages may carry mail content or credentials.
        LOG.error("tool {} failed, request_id={}, cause={}", toolName, ctx.requestId(), cause.getClass().getName());
        ToolError mapped = ImapErrorMapper.map(cause, ctx.requestId()).error();
        if (mapped.details() != null && "relink_required".equals(mapped.details().get("reason"))
                && ctx.linkUrl() != null) {
            mapped = ToolError.relinkRequired(ctx.linkUrl(), ctx.requestId());
        }
        return new ToolOutcome.Failure(afterMutation(toolName, mapped, idempotent, ctx));
    }

    private static ToolOutcome timedOut(String toolName, boolean idempotent, ToolCallContext ctx) {
        ctx.sealMutation(); // decide under the context lock; a later tryBeginMutation() returns false
        return new ToolOutcome.Failure(afterMutation(toolName, ToolError.timeout(ctx.requestId()), idempotent, ctx));
    }
}
