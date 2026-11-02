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

    public ToolHandler wrap(String toolName, ToolHandler delegate) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(delegate, "delegate");
        return (ctx, args) -> execute(toolName, delegate, ctx, args);
    }

    private ToolOutcome execute(String toolName, ToolHandler delegate, ToolCallContext ctx, Map<String, Object> args) {
        Instant start = metrics.now();
        AtomicBoolean cancelled = new AtomicBoolean();
        ToolOutcome outcome = run(toolName, delegate, ctx, args, cancelled);
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

    private ToolOutcome run(String toolName, ToolHandler delegate, ToolCallContext ctx, Map<String, Object> args,
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
            if (future.complete(new ToolOutcome.Failure(ToolError.timeout(ctx.requestId())))) {
                cancelled.set(true);
                LOG.info("tool {} cancelled, request_id={}", toolName, ctx.requestId());
            }
        });
        ctx.onCancel(worker::interrupt);
        if (future.isDone()) { // cancelled before start: no worker, no IMAP I/O
            return result(future, toolName, ctx);
        }
        worker.start();
        try {
            return future.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (future.complete(new ToolOutcome.Failure(ToolError.timeout(ctx.requestId())))) {
                LOG.warn("tool {} exceeded deadline, request_id={}", toolName, ctx.requestId());
            }
            ctx.cancel();
            return result(future, toolName, ctx);
        } catch (InterruptedException e) {
            future.complete(new ToolOutcome.Failure(ToolError.timeout(ctx.requestId())));
            ctx.cancel();
            Thread.currentThread().interrupt();
            return result(future, toolName, ctx);
        } catch (ExecutionException e) {
            return failed(e.getCause(), toolName, ctx);
        }
    }

    /** The already completed future's outcome; the first completion (handler, deadline or cancel) won. */
    private ToolOutcome result(CompletableFuture<ToolOutcome> future, String toolName, ToolCallContext ctx) {
        try {
            return future.join();
        } catch (CompletionException e) {
            return failed(e.getCause(), toolName, ctx);
        }
    }

    private ToolOutcome failed(Throwable cause, String toolName, ToolCallContext ctx) {
        // Class only: exception messages may carry mail content or credentials.
        LOG.error("tool {} failed, request_id={}, cause={}", toolName, ctx.requestId(), cause.getClass().getName());
        return new ToolOutcome.Failure(ImapErrorMapper.map(cause, ctx.requestId()).error());
    }
}
