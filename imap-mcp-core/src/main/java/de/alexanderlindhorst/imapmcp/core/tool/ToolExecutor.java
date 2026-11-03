package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
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
 * <p>Once a write tool marked {@link ToolCallContext#markMutationStarted()}, a deadline, cancel or unchecked
 * failure reports an unknown outcome instead (details.outcome, or OUTCOME_UNKNOWN for non-idempotent tools).
 *
 * <p>The request id is created by the adapter and carried in the {@link ToolCallContext}.
 */
public final class ToolExecutor {

    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(90);

    private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

    private final Duration deadline;
    private final LogIds logIds;
    private final MetricsRecorder metrics;

    public ToolExecutor(Duration deadline, LogIds logIds) {
        this(deadline, logIds, MetricsRecorder.NOOP);
    }

    /**
     * {@code metrics} gets the per-tool call latency (op "call") and, per tenant from the AccountContext, every
     * AUTH_FAILED and TIMEOUT outcome. The pool key is not known here, so these are tenant-level counters.
     */
    public ToolExecutor(Duration deadline, LogIds logIds, MetricsRecorder metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.logIds = Objects.requireNonNull(logIds, "logIds");
    }

    /** For handlers that never mutate (they never mark, so outcomes are the v1 ones). */
    public ToolHandler wrap(String toolName, ToolHandler delegate) {
        return wrap(toolName, true, delegate);
    }

    /** The tool's idempotentHint selects the outcome-unknown code after a mutation started (SPEC 5.5). */
    public ToolHandler wrap(ToolDefinition definition, ToolHandler delegate) {
        Objects.requireNonNull(definition, "definition");
        return wrap(definition.name(), definition.hints().idempotent(), delegate);
    }

    private ToolHandler wrap(String toolName, boolean idempotent, ToolHandler delegate) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(delegate, "delegate");
        return (ctx, args) -> execute(toolName, idempotent, delegate, ctx, args);
    }

    /** A write may have been applied: OUTCOME_UNKNOWN for a non-idempotent tool, else the error plus details.outcome. */
    private static ToolError afterMutation(ToolError error, boolean idempotent, ToolCallContext ctx) {
        if (!ctx.mutationStarted()) {
            return error;
        }
        switch (error.code()) {
            case TIMEOUT, UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> {
                return idempotent ? error.withOutcomeUnknown() : ToolError.outcomeUnknown(ctx.requestId());
            }
            default -> {
                return error;
            }
        }
    }

    private ToolOutcome execute(String toolName, boolean idempotent, ToolHandler delegate, ToolCallContext ctx,
            Map<String, Object> args) {
        Instant start = metrics.now();
        AtomicBoolean cancelled = new AtomicBoolean();
        ToolOutcome outcome = run(toolName, idempotent, delegate, ctx, args, cancelled);
        TenantId tenant = ctx.accountContext().tenantId();
        metrics.operation(tenant, toolName, "call", Duration.between(start, metrics.now()));
        if (outcome instanceof ToolOutcome.Failure f) {
            switch (f.error().code()) {
                case AUTH_FAILED -> metrics.authFailure(tenant);
                case TIMEOUT -> {
                    if (!cancelled.get()) { // an external cancel is not a timeout
                        metrics.timeout(tenant);
                    }
                }
                default -> { }
            }
        }
        return outcome;
    }

    private ToolOutcome run(String toolName, boolean idempotent, ToolHandler delegate, ToolCallContext ctx, Map<String, Object> args,
            AtomicBoolean cancelled) {
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
                future.completeExceptionally(t);
            } finally {
                MDC.clear();
            }
        });
        // First hook: completes the result before the interrupt, so nothing the worker produces afterwards is returned.
        ctx.onCancel(() -> {
            if (future.complete(timedOut(idempotent, ctx))) {
                cancelled.set(true);
                LOG.info("tool {} cancelled, request_id={}", toolName, ctx.requestId());
            }
        });
        ctx.onCancel(worker::interrupt);
        if (future.isDone()) { // cancelled before start: no worker, no IMAP I/O
            return result(future, toolName, idempotent, ctx);
        }
        worker.start();
        try {
            return future.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (future.complete(timedOut(idempotent, ctx))) {
                LOG.warn("tool {} exceeded deadline, request_id={}", toolName, ctx.requestId());
            }
            ctx.cancel();
            return result(future, toolName, idempotent, ctx);
        } catch (InterruptedException e) {
            future.complete(timedOut(idempotent, ctx));
            ctx.cancel();
            Thread.currentThread().interrupt();
            return result(future, toolName, idempotent, ctx);
        } catch (ExecutionException e) {
            return failed(e.getCause(), toolName, idempotent, ctx);
        }
    }

    /** The already completed future's outcome; the first completion (handler, deadline or cancel) won. */
    private ToolOutcome result(CompletableFuture<ToolOutcome> future, String toolName, boolean idempotent,
            ToolCallContext ctx) {
        try {
            return future.join();
        } catch (CompletionException e) {
            return failed(e.getCause(), toolName, idempotent, ctx);
        }
    }

    private ToolOutcome failed(Throwable cause, String toolName, boolean idempotent, ToolCallContext ctx) {
        // Class only: exception messages may carry mail content or credentials.
        LOG.error("tool {} failed, request_id={}, cause={}", toolName, ctx.requestId(), cause.getClass().getName());
        return new ToolOutcome.Failure(afterMutation(ImapErrorMapper.map(cause, ctx.requestId()).error(), idempotent, ctx));
    }

    private static ToolOutcome timedOut(boolean idempotent, ToolCallContext ctx) {
        return new ToolOutcome.Failure(afterMutation(ToolError.timeout(ctx.requestId()), idempotent, ctx));
    }
}
